package com.iosshade.systemui.hooks

import android.view.MotionEvent
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.ShadeEventBus
import com.iosshade.systemui.overlay.ShadeOverlayController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Keeps our custom notification panel OUT of the One UI Control Center.
 *
 * On One UI a pull that starts on the RIGHT side of the status bar opens quick
 * settings / Control Center, while the LEFT side opens notifications. The
 * panel-expansion fraction hook alone cannot tell them apart, so we watch the
 * very first ACTION_DOWN on the shade window and remember which side it was.
 * ShadeOverlayController then skips its overlay for Control Center pulls.
 *
 * Anti-bootloop: best-effort class probing, every hook body in try/catch.
 */
class ControlCenterGuardHook(private val lpparam: LoadPackageParam) {

    fun install() {
        val candidates = listOf(
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.statusbar.window.StatusBarWindowView",
            "com.android.systemui.statusbar.phone.StatusBarWindowView"
        )
        var armed = false
        for (name in candidates) {
            try {
                val clazz = XposedHelpers.findClass(name, lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "dispatchTouchEvent",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val ev = param.args[0] as? MotionEvent ?: return
                                val view = param.thisObject as? android.view.View ?: return
                                val width = view.width
                                if (width <= 0) return
                                when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        // START synchronously: capture the current
                                        // absolute offset and suppress stock BEFORE
                                        // Samsung can draw it (no frame gap).
                                        val currentOffset =
                                            ShadeOverlayController.currentOffsetPx()
                                        com.iosshade.systemui.core.FingerTracker
                                            .down(ev.rawY, currentOffset)
                                        val right = ev.rawX >= width * 0.6f
                                        ShadeEventBus.controlCenterGesture = right
                                        ShadeOverlayController.onGestureDown(right)
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val tracker = com.iosshade.systemui.core.FingerTracker
                                        tracker.move(ev.rawY)
                                        if (!ShadeEventBus.controlCenterGesture) {
                                            ShadeOverlayController.onGestureMove(tracker.offsetPx)
                                        }
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        val tracker = com.iosshade.systemui.core.FingerTracker
                                        val offset = tracker.offsetPx
                                        val velocity = tracker.velocityPxPerSec
                                        if (ev.actionMasked == MotionEvent.ACTION_UP && tracker.active) {
                                            ShadeOverlayController.onFingerRelease(offset, velocity)
                                        }
                                        tracker.up()
                                        ShadeOverlayController.onGestureUp()
                                        if (ShadeEventBus.lastFraction < 0.1f) {
                                            ShadeEventBus.controlCenterGesture = false
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                                // Never propagate into SystemUI touch handling.
                            }
                        }
                    }
                )
                Log.i("ControlCenter guard armed on $name")
                armed = true
            } catch (t: Throwable) {
                Log.w("ControlCenter guard: $name not hookable (${t.message})")
            }
        }
        if (!armed) Log.w("ControlCenter guard: no shade window class resolved")
    }
}
