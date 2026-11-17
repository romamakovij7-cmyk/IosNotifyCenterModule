package com.iosshade.systemui.hooks

import android.os.Handler
import android.os.Looper
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.SafeXposed
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Triggers the SYSTEM keyguard unlock (dismiss) animation once the notification
 * shade has been swiped up and fully collapsed.
 *
 * The signal is emitted from ShadeOverlayController.scheduleStockRestore() after
 * the panel fraction has reached 0 and the stock layer has been restored.
 *
 * No keyguard is forcibly shown: the dismiss animation is issued on the already
 * live SystemUI keyguard managers, so the bouncer / password screen never opens.
 */
class UnlockAnimationHook(private val lpparam: LoadPackageParam) {

    companion object {
        private var kvmRef: WeakReference<Any>? = null
        private var skvmRef: WeakReference<Any>? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        @JvmStatic
        fun requestUnlockAnimation() {
            mainHandler.post {
                try {
                    val kvm = kvmRef?.get()
                    val skvm = skvmRef?.get()
                    if (kvm == null && skvm == null) {
                        Log.w("UnlockAnimationHook: no keyguard manager captured")
                        return@post
                    }

                    // 1) Start the real keyguard dismiss / unlock animation.
                    if (skvm != null) {
                        invokeFirst(
                            skvm,
                            "dismissWithAnimation",
                            "notifyKeyguardAuthenticated",
                            "onKeyguardExitResult",
                            "hide"
                        )
                    }

                    // 2) Complete the unlock on the mediator side.
                    if (kvm != null) {
                        invokeFirst(kvm, "keyguardDone", "dismiss")
                    }

                    Log.i("UnlockAnimationHook: unlock animation triggered")
                } catch (t: Throwable) {
                    Log.e("UnlockAnimationHook.requestUnlockAnimation failed", t)
                }
            }
        }

        /** Invokes the first method name that resolves, trying 0/1 boolean args. */
        private fun invokeFirst(target: Any, vararg names: String) {
            for (name in names) {
                if (SafeXposed.invokeByName(target, name, emptyArray())) return
                if (SafeXposed.invokeByName(target, name, arrayOf(false))) return
                if (SafeXposed.invokeByName(target, name, arrayOf(true))) return
            }
        }
    }

    fun install() {
        hookCaptures(
            "com.android.keyguard.KeyguardViewMediator",
            "com.android.systemui.keyguard.KeyguardViewMediator"
        ) { kvmRef = WeakReference(it) }

        hookCaptures(
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
            "com.android.systemui.keyguard.StatusBarKeyguardViewManager"
        ) { skvmRef = WeakReference(it) }
    }

    private fun hookCaptures(vararg classNames: String, onInstance: (Any) -> Unit) {
        for (name in classNames) {
            try {
                val clazz = SafeXposed.findClassOrNull(name, lpparam.classLoader) ?: continue
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try { onInstance(param.thisObject) } catch (_: Throwable) {}
                    }
                })
                Log.i("UnlockAnimationHook: captured $name")
            } catch (t: Throwable) {
                Log.e("UnlockAnimationHook: capture $name failed", t)
            }
        }
    }
}
