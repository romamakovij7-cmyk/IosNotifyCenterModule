package com.iosshade.systemui.core

/**
 * Tracks the raw finger Y during a shade pull, independently of One UI's
 * non-linear expansion fraction. Holds an ABSOLUTE sheet offset in pixels:
 *
 *   offsetPx = offsetAtDown + (rawY - rawYAtDown)
 *
 * so it works for BOTH directions: opening from collapsed (offset 0 -> h) and
 * closing from expanded (offset h -> 0). The sheet position is derived directly
 * from this value => exact 1:1 finger tracking, no acceleration.
 *
 * Fed synchronously by ControlCenterGuardHook's dispatchTouchEvent hook.
 */
object FingerTracker {
    @Volatile var active: Boolean = false
        private set
    @Volatile var offsetPx: Float = 0f
        private set
    @Volatile var velocityPxPerSec: Float = 0f
        private set

    private var rawStartY = 0f
    private var offsetAtDown = 0f
    private var lastY = 0f
    private var lastT = 0L

    /** Start a gesture at the given absolute sheet offset (usually expansion*h). */
    fun down(y: Float, currentOffset: Float) {
        active = true
        rawStartY = y
        offsetAtDown = currentOffset.coerceAtLeast(0f)
        offsetPx = offsetAtDown
        lastY = y
        lastT = android.os.SystemClock.uptimeMillis()
        velocityPxPerSec = 0f
    }

    fun move(y: Float) {
        if (!active) return
        offsetPx = (offsetAtDown + (y - rawStartY)).coerceAtLeast(0f)
        val now = android.os.SystemClock.uptimeMillis()
        val dt = now - lastT
        if (dt > 0) {
            velocityPxPerSec = ((y - lastY) / dt.toFloat()) * 1000f
            lastY = y
            lastT = now
        }
    }

    fun up() {
        active = false
    }
}
