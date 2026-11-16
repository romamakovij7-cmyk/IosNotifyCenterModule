package com.iosshade.systemui.hooks

import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.SafeXposed
import com.iosshade.systemui.core.ShadeEventBus
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Reliable Control Center detection: hooks the panel controller's own
 * startExpandMotion(x, y, ...) - the exact method One UI calls when a pull
 * gesture begins. This fires BEFORE any expansion-fraction event, so our
 * overlay can never flash open over the quick-settings panel.
 *
 * startExpandMotion's `x` argument is the raw touch X. Right side (>= 60%
 * of the screen width) = Control Center / quick settings.
 */
class ExpandMotionHook(private val lpparam: LoadPackageParam) {

    fun install() {
        val classes = listOf(
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.NotificationPanelViewController"
        )
        var armed = false
        for (name in classes) {
            try {
                val clazz = XposedHelpers.findClass(name, lpparam.classLoader)
                // R8 moves private methods into nest-accessor names like
                // -$$Nest$mstartExpandMotion - hook both spellings.
                var ok = SafeXposed.hookAll(clazz, "startExpandMotion", motionHook)
                ok = SafeXposed.hookAll(clazz, "-\$\$Nest\$mstartExpandMotion", motionHook) || ok
                Log.i("ExpandMotionHook armed on $name (methods=$ok)")
                armed = armed || ok
            } catch (t: Throwable) {
                Log.w("ExpandMotionHook: $name not hookable (${t.message})")
            }
        }
        if (!armed) Log.w("ExpandMotionHook: no panel class resolved")
    }

    private val motionHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                // Nest accessor: arg 0 is the controller itself, x is the first
                // Float among the remaining args (x, y, boolean, height).
                val x = param.args.drop(1).firstOrNull { it is Float } as? Float ?: return
                val screenW = android.content.res.Resources.getSystem()
                    .displayMetrics.widthPixels
                ShadeEventBus.controlCenterGesture = x >= screenW * 0.6f
                Log.i("ExpandMotion x=$x -> controlCenter=${ShadeEventBus.controlCenterGesture}")
            } catch (_: Throwable) {
            }
        }
    }
}
