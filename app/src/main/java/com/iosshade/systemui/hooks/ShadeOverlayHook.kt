package com.iosshade.systemui.hooks

import android.view.LayoutInflater
import android.view.View
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.SafeXposed
import com.iosshade.systemui.overlay.ShadeOverlayController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * PHASE 1 HOOK: find the notification shade's root view and inject our overlay container.
 *
 * Three INDEPENDENT triggers are installed so a rename in any one of them cannot break
 * the module (each trigger is idempotent - [ShadeOverlayController.attach] is a no-op
 * if the overlay already exists):
 *
 *  A) NotificationShadeWindowView CONSTRUCTOR            (view lifecycle - preferred;
 *     onFinishInflate is inherited from View in One UI 9, so it cannot be hooked on
 *     the subclass - the constructor is the reliable earliest point)
 *  B) NotificationShadeWindowViewController CONSTRUCTOR  (controller lifecycle; One UI 9
 *     has no initView() - the view arrives via the constructor and lives in `mView`)
 *  C) LayoutInflater#inflate() filtered to the shade layout resId / result class name
 *                                                  (fallback for One UI layout renames)
 */
class ShadeOverlayHook(private val lpparam: LoadPackageParam) {

    private val loader: ClassLoader get() = lpparam.classLoader

    fun install() {
        installViewFinishInflateTrigger()
        installControllerInitViewTrigger()
        installInflateFallbackTrigger()
    }

    // ------------------------------------------------------------------
    // Trigger A: constructor of the shade window view.
    // One UI 9 inherits onFinishInflate() from android.view.View, so hooking it on the
    // subclass finds nothing ("class exists, signature differs"). The constructor is
    // declared for sure; children are not inflated yet inside it, so attach is posted.
    // ------------------------------------------------------------------
    private fun installViewFinishInflateTrigger() {
        val candidates = listOf(
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.statusbar.phone.NotificationShadeView"
        )
        var found = false
        for (name in candidates) {
            val clazz = SafeXposed.findClassOrNull(name, loader) ?: continue
            found = true
            try {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            (param.thisObject as? View)?.let { view ->
                                // post(): layout children are not ready inside the constructor.
                                view.post { ShadeOverlayController.attach(view) }
                            }
                        } catch (t: Throwable) {
                            Log.e("shade view ctor attach failed", t)
                        }
                    }
                })
                Log.i("Hooked ${clazz.name} constructors (trigger A)")
            } catch (t: Throwable) {
                Log.e("trigger A failed for $name", t)
            }
        }
        if (!found) Log.w("ShadeOverlayHook: shade window view candidates not found (trigger A inactive)")
    }

    // ------------------------------------------------------------------
    // Trigger B: controller constructor. One UI 9 has no initView(...) - the view is
    // passed to the constructor and stored in the public final field `mView`.
    // ------------------------------------------------------------------
    private fun installControllerInitViewTrigger() {
        val candidates = listOf(
            "com.android.systemui.shade.NotificationShadeWindowViewController",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowViewController"
        )
        for (name in candidates) {
            val clazz = SafeXposed.findClassOrNull(name, loader) ?: continue
            try {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = SafeXposed.getObjectFieldSafe(param.thisObject, "mView") as? View
                                ?: param.args.firstOrNull { it is View } as? View
                                ?: return
                            view.post { ShadeOverlayController.attach(view) }
                        } catch (t: Throwable) {
                            Log.e("controller ctor attach failed", t)
                        }
                    }
                })
                Log.i("Hooked ${clazz.name} constructors (trigger B)")
            } catch (t: Throwable) {
                Log.e("trigger B failed for $name", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // Trigger C: filtered LayoutInflater.inflate() fallback.
    // Matches either the shade layout resource id or the inflated class name,
    // so it survives a Samsung layout rename as long as the view class survives.
    // ------------------------------------------------------------------
    private var cachedShadeResId: Int? = null
    private var shadeResLookupDone = false

    private fun shadeLayoutResId(): Int? {
        if (shadeResLookupDone) return cachedShadeResId
        shadeResLookupDone = true
        return try {
            val rClass = XposedHelpers.findClass("com.android.systemui.R\$layout", loader)
            val id = XposedHelpers.getStaticObjectField(rClass, "status_bar_notification_shade") as Int
            cachedShadeResId = id
            id
        } catch (t: Throwable) {
            // Cache the FAILURE too: inflate() runs hundreds of times per minute and
            // must not spam the log on every pass (One UI 9 ships no R$layout class).
            Log.w("R\$layout.status_bar_notification_shade unresolved (${t.message}) - " +
                "trigger C will rely on class-name matching only (one-time warning)")
            null
        }
    }

    private fun installInflateFallbackTrigger() {
        try {
            XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val result = param.result as? View ?: return
                        val resId = param.args.getOrNull(0) as? Int
                        val matches =
                            result.javaClass.name.contains("NotificationShadeWindowView") ||
                                (resId != null && resId == shadeLayoutResId())
                        if (matches) {
                            // post(): layout children are not ready inside inflate itself.
                            result.post { ShadeOverlayController.attach(result) }
                        }
                    } catch (t: Throwable) {
                        Log.e("inflate fallback hook failed", t)
                    }
                }
            })
            Log.i("ShadeOverlayHook: LayoutInflater.inflate fallback installed (trigger C)")
        } catch (t: Throwable) {
            Log.e("ShadeOverlayHook: inflate fallback could not be installed", t)
        }
    }
}
