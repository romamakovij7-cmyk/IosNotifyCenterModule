package com.iosshade.systemui.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny main-thread event bus that decouples SystemUI hooks from the overlay UI.
 *
 * Hooks (which may run on arbitrary threads during panel animation frames) only POST events;
 * all listeners run on the main thread, so the overlay view tree is only touched from UI thread.
 * Every listener invocation is individually wrapped so one bad listener can never break others.
 */
object ShadeEventBus {

    /** Mirrors com.android.systemui.statusbar.StatusBarStateController states. */
    const val STATE_SHADE = 0
    const val STATE_KEYGUARD = 1
    const val STATE_SHADE_LOCKED = 2

    interface Listener {
        /** Called on the main thread with the current panel expansion fraction (0f..1f). */
        fun onExpandedFractionChanged(fraction: Float) {}

        /** Called on the main thread when the status bar state changes (shade / keyguard / shade_locked). */
        fun onStatusBarStateChanged(state: Int) {}

        /** Called on the main thread when the notification set changed. */
        fun onNotificationsChanged() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Last known values, readable synchronously (volatile: written from hook threads). */
    @Volatile
    var lastFraction: Float = 0f
        private set

    /**
     * The stock panel's expansion HEIGHT in pixels (mExpandedHeight), published
     * alongside fraction. Using real px instead of fraction*screenHeight keeps
     * the overlay glued 1:1 to the finger (One UI's maxPanelHeight != screen).
     * -1 means "unknown" -> fall back to fraction.
     */
    @Volatile
    var lastExpandedHeightPx: Float = -1f
        private set

    @Volatile
    var lastState: Int = STATE_SHADE
        private set

    /**
     * True while the user's finger started the pull on the RIGHT side of the
     * screen (= One UI Control Center / quick settings). Set by the touch hook;
     * our custom notification panel must stay OUT of the way then.
     */
    @Volatile
    var controlCenterGesture: Boolean = false

    fun register(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    // Coalescing: 3 fraction hooks fire per frame; posting each floods the main
    // queue. A single pending Runnable reads the latest value => one update/frame.
    private var fractionPostPending = false
    private val fractionRunnable = Runnable {
        fractionPostPending = false
        val value = lastFraction
        listeners.forEach { l ->
            runCatching { l.onExpandedFractionChanged(value) }
                .onFailure { Log.e("Listener.onExpandedFractionChanged failed", it) }
        }
    }

    fun postExpandedFraction(fraction: Float) {
        lastFraction = fraction.coerceIn(0f, 1f)
        if (!fractionPostPending) {
            fractionPostPending = true
            mainHandler.post(fractionRunnable)
        }
    }

    /** Publish the real expanded height (px) from setExpandedHeightInternal. */
    fun postExpandedHeight(px: Float) {
        lastExpandedHeightPx = px
    }

    fun postState(state: Int) {
        lastState = state
        mainHandler.post {
            listeners.forEach { l ->
                runCatching { l.onStatusBarStateChanged(state) }
                    .onFailure { Log.e("Listener.onStatusBarStateChanged failed", it) }
            }
        }
    }

    fun postNotificationsChanged() {
        mainHandler.post {
            listeners.forEach { l ->
                runCatching { l.onNotificationsChanged() }
                    .onFailure { Log.e("Listener.onNotificationsChanged failed", it) }
            }
        }
    }
}
