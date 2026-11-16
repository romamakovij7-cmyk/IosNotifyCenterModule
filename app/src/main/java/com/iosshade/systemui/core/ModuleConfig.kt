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
     * Show the custom panel on the lockscreen (shade_locked / keyguard states) too.
     * When true, only notifications with VISIBILITY_PUBLIC are rendered on the keyguard,
     * mirroring One UI's own privacy filtering.
     */
    const val SHOW_ON_KEYGUARD = true

    /** Tag used to make overlay attachment idempotent across multiple hook triggers. */
    const val TAG_OVERLAY = "ios_notif_shade_overlay"

    /**
     * Set to true to dump every resolved panel-controller method into the LSPosed log.
     * Use this during a JADX calibration pass (see README) to discover the exact
     * One UI 9 method names, then copy them into the candidate lists.
     */
    const val DUMP_METHODS = false
}
