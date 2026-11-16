package com.iosshade.systemui.hooks

import android.view.View
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.overlay.ShadeOverlayController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Pins the stock notification stack (and its footer with the "Clear all" pill)
 * to INVISIBLE while our iOS overlay is showing.
 *
 * Two complementary interceptions per class, because One UI defeats a plain
 * view-level hide by calling setVisibility(VISIBLE) during its own animations:
 *
 *  1. setVisibility(int)  - requests other than INVISIBLE are forced down while
 *     our overlay is up (and the view registers itself on first sight).
 *  2. onAttachedToWindow  - guarantees we know EVERY instance the moment it
 *     enters the hierarchy, even if our tree search never finds it (One UI 9
 *     occasionally re-parents shade content).
 *
 * Both hooks are pass-through no-ops whenever our overlay is hidden, so stock
 * behaviour is untouched otherwise. Anti-bootloop contract: every step guarded;
 * a missing/renamed class only disables pinning for that view (WARN log).
 */
class StockShadeSuppressorHook(private val lpparam: LoadPackageParam) {

    fun install() {
        val targets = listOf(
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
            "com.android.systemui.statusbar.notification.footer.ui.view.FooterView",
            // Samsung's lock-screen clock/date container (may live in another window -
            // process-wide hooks catch it regardless of where it is attached).
            "com.android.systemui.keyguard.ui.view.KeyguardRootView"
        )
        for (name in targets) {
            armVisibility(name)
            armAttach(name)
        }
    }

    private fun armVisibility(className: String) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!ShadeOverlayController.isSuppressingStock()) return
                            val view = param.thisObject as? View ?: return
                            if ((param.args[0] as Int) == View.INVISIBLE) return
                            ShadeOverlayController.registerSuppressed(view)
                            param.args[0] = View.INVISIBLE
                        } catch (_: Throwable) {
                            // Never propagate into SystemUI.
                        }
                    }
                }
            )
            Log.i("Suppressor armed (visibility) for $className")
        } catch (t: Throwable) {
            Log.w("Suppressor: $className setVisibility not hookable (${t.message})")
        }
    }

    private fun armAttach(className: String) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!ShadeOverlayController.isSuppressingStock()) return
                            val view = param.thisObject as? View ?: return
                            // registerSuppressed forces INVISIBLE while we are showing.
                            ShadeOverlayController.registerSuppressed(view)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            Log.i("Suppressor armed (attach) for $className")
        } catch (t: Throwable) {
            Log.w("Suppressor: $className onAttachedToWindow not hookable (${t.message})")
        }
    }
}
