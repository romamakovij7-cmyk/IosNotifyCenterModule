package com.iosshade.systemui.hooks

import android.view.View
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.overlay.ShadeOverlayController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Keeps SAMSUNG'S ORIGINAL lock-screen clock visible while our panel is up.
 *
 * One UI re-hides its SecKeyguardStatusView / clock container on every frame
 * when the shade is open over the home screen, which beats a one-shot
 * setVisibility(VISIBLE). Forcing the argument in setVisibility() itself wins
 * that race. Pass-through no-op whenever our overlay is hidden, so the stock
 * lock screen behaves completely normally otherwise.
 */
class ClockForceHook(private val lpparam: LoadPackageParam) {

    fun install() {
        val targets = listOf(
            "com.samsung.android.app.facewidget.SecKeyguardStatusView",
            "com.samsung.android.app.facewidget.SecKeyguardClockContainer"
        )
        var armed = false
        for (name in targets) {
            try {
                val clazz = XposedHelpers.findClass(name, lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "setVisibility",
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!ShadeOverlayController.isSuppressingStock()) return
                                param.args[0] = View.VISIBLE
                            } catch (_: Throwable) {
                                // Never propagate into SystemUI.
                            }
                        }
                    }
                )
                Log.i("ClockForce armed on $name")
                armed = true
            } catch (t: Throwable) {
                Log.w("ClockForce: $name not hookable (${t.message})")
            }
        }
        if (!armed) Log.w("ClockForce: no clock class resolved")
    }
}
