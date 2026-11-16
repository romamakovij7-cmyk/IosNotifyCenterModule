package com.iosshade.systemui.core

/**
 * Tunable constants for the MVP.
 *
 * Everything here is intentionally conservative: the module must degrade to stock One UI
 * behaviour whenever something is missing, never crash, never leave the user stuck.
 */
object ModuleConfig {

    /** Overlay becomes visible once the stock panel is at least this expanded. */
    const val MIN_SHOW_FRACTION = 0.04f

    /**
     * Once shown, the panel hides only BELOW this fraction. The gap between the
     * two thresholds (hysteresis) stops One UI's settle-bounce from flickering
     * hide/show cycles at the bottom of the pull.
     */
    const val HIDE_FRACTION = 0.02f

    /**
     * Show the custom panel on the lockscreen (shade_locked / keyguard states) too.
     * The user asked for the module to stay OFF the lock screen - stock One UI
     * keyguard stays fully intact there.
     */
    const val SHOW_ON_KEYGUARD = false

    /** Tag used to make overlay attachment idempotent across multiple hook triggers. */
    const val TAG_OVERLAY = "ios_notif_shade_overlay"

    /**
     * Set to true to dump every resolved panel-controller method into the LSPosed log.
     * Use this during a JADX calibration pass (see README) to discover the exact
     * One UI 9 method names, then copy them into the candidate lists.
     */
    const val DUMP_METHODS = false

    /**
     * TEMPORARY diagnostic: on every panel show, dump all process windows and any
     * clock/keyguard-ish view classes, so the ghost lock-clock can be identified
     * and pinned. Turn off once calibrated. (Calibration complete: the ghost was
     * QSClockHeaderView + QSClockPopOverImmersiveView, now matched.)
     */
    const val DUMP_WINDOW_VIEWS = false
}
