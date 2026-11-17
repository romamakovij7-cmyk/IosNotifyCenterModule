package com.iosshade.systemui

import android.os.Build
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.hooks.NotificationSourceHook
import com.iosshade.systemui.hooks.StockShadeSuppressorHook
import com.iosshade.systemui.hooks.PanelExpansionHook
import com.iosshade.systemui.hooks.ShadeOverlayHook
import com.iosshade.systemui.hooks.ControlCenterGuardHook
import com.iosshade.systemui.hooks.ClockForceHook
import com.iosshade.systemui.hooks.UnlockAnimationHook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * LSPosed entry point for the iOS Notification Shade module.
 *
 * Loaded ONLY into com.android.systemui (declared in assets/xposed_init + xposedscope).
 *
 * Anti-bootloop contract:
 *  - handleLoadPackage itself is fully wrapped in try/catch(Throwable).
 *  - Each hook group is installed inside its own guard: one failing group can never
 *    prevent the others from installing, and NO failure ever propagates into SystemUI.
 *  - Every individual hook body (see the hooks/ package) is additionally wrapped in try/catch,
 *    so a One UI 9 rename degrades to "stock behaviour + WARN log", never a crash loop.
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
    }

    override fun initZygote(startupParam: StartupParam) {
        try {
            Log.i("Zygote init: modulePath=${startupParam.modulePath}")
        } catch (t: Throwable) {
            // Logging must never break zygote.
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI) return
        // The shade only exists in the main SystemUI process. Samsung also runs
        // com.android.systemui:edgelighting (and others) under the same package name;
        // skipping them halves the log noise and removes needless hook surface.
        if (lpparam.processName != SYSTEM_UI) {
            try {
                Log.i("Skipping subprocess ${lpparam.processName} (shade lives in the main process)")
            } catch (t: Throwable) {
                // Logging must never break anything.
            }
            return
        }

        try {
            Log.i(
                "SystemUI loaded: process=${lpparam.processName}, " +
                    "sdk=${Build.VERSION.SDK_INT}, display=${Build.DISPLAY}"
            )

            // Order matters: the overlay must be able to attach before expansion events arrive,
            // but the event bus keeps the last values, so this order is belt-and-braces only.
            installGroup("ShadeOverlayHook") { ShadeOverlayHook(lpparam).install() }
            installGroup("PanelExpansionHook") { PanelExpansionHook(lpparam).install() }
            installGroup("NotificationSourceHook") { NotificationSourceHook(lpparam).install() }
            installGroup("StockShadeSuppressorHook") { StockShadeSuppressorHook(lpparam).install() }
            installGroup("ControlCenterGuardHook") { ControlCenterGuardHook(lpparam).install() }
            installGroup("ExpandMotionHook") { com.iosshade.systemui.hooks.ExpandMotionHook(lpparam).install() }
            installGroup("ClockForceHook") { ClockForceHook(lpparam).install() }
            installGroup("UnlockAnimationHook") { UnlockAnimationHook(lpparam).install() }

            Log.i("All hook groups installed. Review WARN/ERROR lines above for One UI 9 calibration.")
        } catch (t: Throwable) {
            Log.e("handleLoadPackage failed - stock SystemUI continues untouched", t)
        }
    }

    /** Installs one hook group; any failure is logged and swallowed. */
    private inline fun installGroup(name: String, block: () -> Unit) {
        try {
            block()
            Log.i("$name installed")
        } catch (t: Throwable) {
            Log.e("$name install failed - group disabled, stock behaviour preserved", t)
        }
    }
}
