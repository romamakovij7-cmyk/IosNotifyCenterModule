package com.iosshade.systemui.core

/**
 * Tracks the raw finger Y during a shade pull, independently of One UI's
 * non-linear expansion fraction. The sheet position is derived directly from
 * `rawY - startY`, so it follows the finger 1:1 with no acceleration.
 *
 * Fed by ControlCenterGuardHook (which already sees every touch on the shade
 * and status-bar windows). Read by IOSNotificationShadeView.applyExpansion.
 */
object FingerTracker {
    @Volatile var active: Boolean = false
        private set
    @Volatile var offsetPx: Float = 0f
        private set

    private var startY = 0f

    fun down(y: Float) {
        active = true
        startY = y
        offsetPx = 0f
    }

    fun move(y: Float) {
        if (active) offsetPx = (y - startY).coerceIn(0f, 10000f)
    }

    fun up() {
        active = false
    }
}
