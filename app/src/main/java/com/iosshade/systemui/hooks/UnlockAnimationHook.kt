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
 * Plays Samsung's REAL unlock transition after the notification shade closes:
 *  1) playCannedUnlockAnimation()   -> wallpaper blur/zoom + shade dissolve
 *  2) launcherUnlockController.playUnlockAnimation()  -> launcher ICONS animation
 *
 * Both are reached through KeyguardViewMediator.mKeyguardUnlockAnimationControllerLazy.get().
 * No keyguard is shown, so no password/PIN screen can appear.
 */
class UnlockAnimationHook(private val lpparam: LoadPackageParam) {

    companion object {
        private var kvmRef: WeakReference<Any>? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        @JvmStatic
        fun requestUnlockAnimation() {
            mainHandler.post {
                try {
                    val kvm = kvmRef?.get()
                    if (kvm == null) {
                        Log.w("UnlockAnimationHook: no KeyguardViewMediator captured")
                        return@post
                    }

                    // mKeyguardUnlockAnimationControllerLazy is a dagger.Lazy.
                    val lazyController = SafeXposed.findFieldValue(
                        kvm, listOf("mKeyguardUnlockAnimationControllerLazy")
                    )
                    val controller = SafeXposed.callMethodSafe(lazyController, "get")
                        ?: lazyController

                    if (controller == null) {
                        Log.w("UnlockAnimationHook: unlock animation controller is null")
                        return@post
                    }

                    // 1) Wallpaper / shade unlock animation.
                    val played = SafeXposed.invokeByName(controller, "playCannedUnlockAnimation", emptyArray())

                    // 2) Launcher ICONS unlock animation (the one visible when the
                    //    phone is unlocked normally). The launcher proxy is stored
                    //    in the `launcherUnlockController` field of the controller.
                    val launcherProxy = SafeXposed.findFieldValue(
                        controller, listOf("launcherUnlockController")
                    )
                    var iconsPlayed = false
                    if (launcherProxy != null) {
                        // playUnlockAnimation(long duration, long delay)
                        iconsPlayed = SafeXposed.invokeByName(
                            launcherProxy, "playUnlockAnimation",
                            arrayOf(200L, 0L)
                        )
                        if (!iconsPlayed) {
                            // Some builds take a single duration arg.
                            iconsPlayed = SafeXposed.invokeByName(
                                launcherProxy, "playUnlockAnimation", arrayOf(200L)
                            )
                        }
                    }

                    Log.i("UnlockAnimationHook: canned=$played icons=$iconsPlayed")
                } catch (t: Throwable) {
                    Log.e("UnlockAnimationHook failed", t)
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
                Log.i("UnlockAnimationHook: captured $name")
            } catch (t: Throwable) {
                Log.e("UnlockAnimationHook: capture $name failed", t)
            }
        }
    }
}
