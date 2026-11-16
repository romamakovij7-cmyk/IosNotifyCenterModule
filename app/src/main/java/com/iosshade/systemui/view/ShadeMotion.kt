package com.iosshade.systemui.view

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.animation.ValueAnimator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * ONE authoritative expansion value for the notification center, adapted from
 * Android's PanelViewController / NotificationPanelViewController mechanics:
 *
 *  - touch SLOP before a gesture starts tracking (a tap is not a drag),
 *  - VelocityTracker on RAW screen coordinates (the window frame can move),
 *  - velocity-aware release decision (fast flick up closes, fast down opens),
 *  - direction-reversal aware: a small drag settles back, a committed one flings,
 *  - spring-like settling with a small overshoot, then a settle-back,
 *  - gesture INTERRUPTION: a new DOWN cancels any running settle animation so
 *    tracking continues from the CURRENT visual value - no jump.
 *
 * 0.0 = fully collapsed, 1.0 = fully expanded. Everything visual derives from
 * [expansion] through [Host.onExpansionChanged]; nobody writes view transforms
 * directly from touch events any more.
 */
class ShadeMotion(context: Context, private val host: Host) {

    interface Host {
        /** Called on every expansion change (touch OR settle). */
        fun onExpansionChanged(expansion: Float, velocity: Float, tracking: Boolean)
        /** Drive the stock panel so it never fights our authoritative value. */
        fun onDriveStock(fraction: Float)
        /** Content can still scroll down (so an upward swipe must scroll, not close). */
        fun canScrollContentDown(): Boolean
        /** Screen height in px, used to convert drag distance to expansion. */
        fun screenHeight(): Int
    }

    /** The single source of truth. */
    var expansion: Float = 0f
        private set
    var velocity: Float = 0f
        private set
    var isTracking: Boolean = false
        private set

    /** True while a settle/fling animation is running (used for the edge). */
    val isAnimating: Boolean
        get() = settling != null

    private val vc = ViewConfiguration.get(context)
    private val touchSlop = vc.scaledTouchSlop
    private val minFling = vc.scaledMinimumFlingVelocity.toFloat()
    private val maxFling = vc.scaledMaximumFlingVelocity.toFloat()

    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var downExpansion = 0f
    private var slopPassed = false
    private var settling: ValueAnimator? = null

    /** Called from the overlay's own touch listener. Returns true when consumed. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Interruption: cancel any in-flight settle, keep current value.
                cancelSettling()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                downY = ev.rawY
                downExpansion = expansion
                slopPassed = false
                isTracking = false
                return false // let children (cards) see the DOWN first
            }

            MotionEvent.ACTION_MOVE -> {
                val t = tracker ?: return false
                addRawMovement(t, ev)
                val dy = ev.rawY - downY
                if (!slopPassed && abs(dy) < touchSlop) return false

                // Only start tracking when the gesture can actually move the
                // panel: upward = close (unless the list still scrolls down),
                // downward = open.
                if (!slopPassed) {
                    val canClose = dy < 0 && !host.canScrollContentDown()
                    val canOpen = dy > 0 && expansion < 1f
                    if (!canClose && !canOpen) return false
                    slopPassed = true
                    isTracking = true
                }

                val h = host.screenHeight().coerceAtLeast(1)
                val next = (downExpansion + dy / h).coerceIn(0f, 1f)
                t.computeCurrentVelocity(1000)
                velocity = t.yVelocity.coerceIn(-maxFling, maxFling)
                setExpansion(next, velocity, tracking = true)
                host.onDriveStock(next)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker ?: return false
                addRawMovement(t, ev)
                t.computeCurrentVelocity(1000)
                velocity = t.yVelocity
                val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                slopPassed = false
                isTracking = false
                tracker?.recycle()
                tracker = null
                if (cancelled) {
                    settleTo(downExpansion)
                } else {
                    decideAndSettle()
                }
                return false
            }

            else -> return false
        }
    }

    /** Mirrors the stock panel's expansion while we are NOT driving it. */
    fun onExternalFraction(fraction: Float) {
        if (isTracking || settling != null) return
        setExpansion(fraction.coerceIn(0f, 1f), velocity, tracking = false)
    }

    /** Velocity-aware release: same decision tree as PanelViewController.fling(). */
    private fun decideAndSettle() {
        val v = velocity
        val target = when {
            abs(v) < minFling -> {
                // Slow release: decide by how far we got (AOSP: fraction > 0.5).
                if (expansion >= 0.5f) 1f else 0f
            }
            v > 0 -> 1f // flick down = expand
            else -> 0f  // flick up = collapse
        }
        settleTo(target)
    }

    /** Spring-like settle: decelerate curve + tiny overshoot + settle-back. */
    private fun settleTo(target: Float) {
        cancelSettling()
        val start = expansion
        if (abs(target - start) < 0.001f) {
            setExpansion(target, 0f, tracking = false)
            host.onDriveStock(target)
            return
        }
        val distance = abs(target - start)
        val duration = (110L + (distance * 150L)).toLong().coerceIn(110L, 260L)
        val animator = ValueAnimator.ofFloat(start, target).apply {
            this.duration = duration
            interpolator = SETTLE_INTERPOLATOR
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                setExpansion(f.coerceIn(0f, 1f), 0f, tracking = false)
                host.onDriveStock(f.coerceIn(0f, 1f))
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (settling == a) settling = null
                    // Snap to the exact target so no rounding gap remains.
                    setExpansion(target, 0f, tracking = false)
                    host.onDriveStock(target)
                }
                override fun onAnimationCancel(a: android.animation.Animator) {
                    if (settling == a) settling = null
                }
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
        }
        settling = animator
        animator.start()
    }

    /** True while our finger or a settle animation still owns the panel. */
    fun isMotionActive(): Boolean = isTracking || settling != null

    fun cancelSettling() {
        settling?.cancel()
        settling = null
    }

    fun snapTo(value: Float) {
        cancelSettling()
        setExpansion(value.coerceIn(0f, 1f), 0f, tracking = false)
    }

    private fun setExpansion(value: Float, v: Float, tracking: Boolean) {
        if (value == expansion && v == velocity && tracking == isTracking) return
        expansion = value
        velocity = v
        isTracking = tracking
        host.onExpansionChanged(value, v, tracking)
    }

    /** Add movement using raw coordinates (AOSP PanelViewController.addMovement). */
    private fun addRawMovement(t: VelocityTracker, ev: MotionEvent) {
        val dx = ev.rawX - ev.x
        val dy = ev.rawY - ev.y
        ev.offsetLocation(dx, dy)
        t.addMovement(ev)
        ev.offsetLocation(-dx, -dy)
    }

    companion object {
        /** Smooth decelerate with a subtle overshoot, mirroring AOSP's spring settle. */
        val SETTLE_INTERPOLATOR: Interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1.02f)
    }
}
