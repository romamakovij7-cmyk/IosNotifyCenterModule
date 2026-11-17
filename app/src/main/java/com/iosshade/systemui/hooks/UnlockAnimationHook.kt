package com.iosshade.systemui.hooks

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.SafeXposed
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Real Samsung lock-screen unlock animation after the shade closes.
 *
 * Sequence (the same calls the stock unlock path makes):
 *   1) showLocked()        -> genuine lock screen appears
 *   2) dismissWithAnimation() -> the real unlock transition plays
 *   3) keyguardDone(true)  -> COMPLETES the unlock (without this the device
 *                             stays locked and the bouncer/password shows)
 *
 * Captured via constructor hooks, so no method-signature guessing. Every step
 * is best-effort + try/catch; a miss simply skips the animation.
 */
class UnlockAnimationHook(private val lpparam: LoadPackageParam) {

    companion object {
        private var kvmRef: WeakReference<Any>? = null
        private var skvmRef: WeakReference<Any>? = null
        private val handler = Handler(Looper.getMainLooper())

        private const val SHOW_DELAY_MS = 1500L
        private const val DISMISS_DELAY_MS = 350L

        @JvmStatic
        fun playUnlockAnimation() {
            handler.post {
                try {
                    val kvm = kvmRef?.get()
                    val skvm = skvmRef?.get()
                    if (kvm == null && skvm == null) {
                        Log.w("UnlockAnimationHook: no keyguard instance")
                        return@post
                    }

                    // 1) Show the genuine lock screen.
                    if (kvm != null) {
                        SafeXposed.callMethodSafe(kvm, "showLocked", null as Any?)
                        SafeXposed.callMethodSafe(kvm, "showLocked", Bundle())
                    }
                    if (skvm != null) {
                        SafeXposed.callMethodSafe(skvm, "show", null as Any?)
                    }
                    Log.i("UnlockAnimationHook: lock screen shown")

                    // 2) After it settles, play the real unlock animation and
                    //    COMPLETE the unlock so the device returns to home.
                    handler.postDelayed({
                        try {
                            if (skvm != null) {
                                SafeXposed.callMethodSafe(skvm, "dismissWithAnimation")
                            }
                            if (kvm != null) {
                                SafeXposed.callMethodSafe(kvm, "dismiss")
                                SafeXposed.callMethodSafe(kvm, "keyguardDone", true)
                            }
                            Log.i("UnlockAnimationHook: dismiss + keyguardDone(true)")

                            // Safety retry: some builds ignore the first done()
                            // if the keyguard was still animating in.
                            handler.postDelayed({
                                if (kvm != null) {
                                    SafeXposed.callMethodSafe(kvm, "keyguardDone", true)
                                }
                            }, 900)
                        } catch (t: Throwable) {
                            Log.e("UnlockAnimationHook dismiss failed", t)
                        }
                    }, SHOW_DELAY_MS + DISMISS_DELAY_MS)
                } catch (t: Throwable) {
                    Log.e("UnlockAnimationHook play failed", t)
                }
            }
        }
    }

    fun install() {
        for (name in listOf(
            "com.android.keyguard.KeyguardViewMediator",
            "com.android.systemui.keyguard.KeyguardViewMediator"
        )) {
            try {
                val clazz = SafeXposed.findClassOrNull(name, lpparam.classLoader) ?: continue
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try { kvmRef = WeakReference(param.thisObject) } catch (_: Throwable) {}
                    }
                })
                Log.i("UnlockAnimationHook: hooked $name")
            } catch (t: Throwable) {
                Log.e("UnlockAnimationHook: $name hook failed", t)
            }
        }
        for (name in listOf(
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
            "com.android.systemui.keyguard.StatusBarKeyguardViewManager"
        )) {
            try {
                val clazz = SafeXposed.findClassOrNull(name, lpparam.classLoader) ?: continue
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try { skvmRef = WeakReference(param.thisObject) } catch (_: Throwable) {}
                    }
                })
                Log.i("UnlockAnimationHook: hooked $name")
            } catch (t: Throwable) {
                Log.e("UnlockAnimationHook: $name hook failed", t)
            }
        }
    }
}
