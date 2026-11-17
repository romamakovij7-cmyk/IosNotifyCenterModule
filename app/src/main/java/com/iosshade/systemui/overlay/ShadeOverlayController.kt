package com.iosshade.systemui.overlay

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.ModuleConfig
import com.iosshade.systemui.core.ShadeEventBus
import com.iosshade.systemui.hooks.PanelExpansionHook
import com.iosshade.systemui.data.NotifCollector
import com.iosshade.systemui.view.IOSNotificationShadeView
import java.lang.ref.WeakReference

/**
 * Owns the lifecycle of the injected iOS-style overlay:
 *
 *   attach(root)            - idempotent; adds the overlay as the top child of the shade root
 *   onExpandedFraction      - show when the stock panel is pulled down, hide when it closes
 *   onStatusBarStateChanged - keyguard-aware rendering (privacy filtering)
 *   onNotificationsChanged  - marks the content dirty, re-renders lazily
 *
 * Stock-shade suppression (Phase 2.1 - "delete the original notification center"):
 *  - While our overlay is up, the stock NotificationStackScrollLayout and its
 *    FooterView ("Clear all" button) are kept INVISIBLE.
 *  - Three independent layers keep them invisible:
 *      1. DFS hide at show-time (tag + INVISIBLE, id/class-name match)
 *      2. StockShadeSuppressorHook intercepting stock setVisibility() re-shows
 *      3. reassertHidden() on every shade event while shown
 *  - On hide, everything tagged is restored to VISIBLE. The hook becomes a
 *    pass-through the moment `shown` goes false, so stock can never stay stuck.
 *
 * Safety behaviour (anti-stuck rules):
 *  - If there is nothing to show (empty list), the overlay stays GONE, the stock
 *    stack is restored, and the One UI shade remains fully visible/interactive.
 *  - All view mutations happen on the main thread (attach() self-posts if needed).
 *  - Every entry point is wrapped in try/catch(Throwable).
 */
object ShadeOverlayController : ShadeEventBus.Listener {

    private const val TAG_STOCK_HIDDEN = "iosshade_hidden_stock"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachLock = Any()

    private var overlayRef: WeakReference<IOSNotificationShadeView>? = null
    private var rootRef: WeakReference<ViewGroup>? = null

    /** Stock views we suppressed; restored on hide. Main-thread only. */
    private val hiddenStock = ArrayList<WeakReference<View>>()

    /** True while the overlay is on screen. Read from the suppressor hook (main thread). */
    @Volatile
    private var shown = false

    /** True while the panel state is keyguard (drives keyguard-content suppression). */
    @Volatile
    private var keyguard = false

    /** Guards against re-entering hideOverlay while a hide animation is in flight. */
    private var hiding = false

    /**
     * Heartbeat: One UI re-shows its stack/clock whenever IT decides to (layout
     * passes, its own animations), not only during swipes - so per-event
     * re-assertion is not enough. While our overlay is up, this re-pins
     * everything every 300 ms; cancelled the moment we hide.
     */
    private val suppressionTick = object : Runnable {
        override fun run() {
            try {
                if (!shown) return
                val searchFrom = rootRef?.get()?.rootView
                if (searchFrom != null) collectStockStacks(searchFrom, 0)
                reassertHidden()
                killShadeBlur()
                mainHandler.postDelayed(this, 300)
            } catch (_: Throwable) {
                // Never let the heartbeat die loudly; next show re-arms it anyway.
            }
        }
    }

    private fun startSuppressionTick() {
        mainHandler.removeCallbacks(suppressionTick)
        mainHandler.postDelayed(suppressionTick, 300)
    }

    /** True when the notification data changed since the last render (main-thread only). */
    @Volatile
    private var dirty = true

    /** True while our overlay covers the shade - the stock stack must stay invisible. */
    fun isSuppressingStock(): Boolean = shown

    /**
     * Called by StockShadeSuppressorHook when a stock stack/footer instance is
     * seen (re-show attempt or attach). Tags it and remembers it for restore;
     * forces it invisible right away while our overlay is up.
     */
    fun registerSuppressed(view: View) {
        try {
            if (view.tag != TAG_STOCK_HIDDEN) {
                view.tag = TAG_STOCK_HIDDEN
                hiddenStock.add(WeakReference(view))
                Log.i("Suppressor pinned ${view.javaClass.simpleName}")
            }
            if (shown && view.visibility != View.INVISIBLE) {
                view.visibility = View.INVISIBLE
            }
        } catch (t: Throwable) {
            Log.e("registerSuppressed failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Attachment
    // ------------------------------------------------------------------
    fun attach(root: View) {
        // Attach must always run on the UI thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { attach(root) }
            return
        }
        try {
            val parent = root as? ViewGroup ?: run {
                Log.w("attach(): root is not a ViewGroup (${root.javaClass.name}) - skipped")
                return
            }

            synchronized(attachLock) {
                // Idempotency check 1: tag already present in this hierarchy.
                if (parent.findViewWithTag<IOSNotificationShadeView>(ModuleConfig.TAG_OVERLAY) != null) {
                    return
                }
                // Idempotency check 2: our weak overlay is already attached to this root.
                val existing = overlayRef?.get()
                if (existing != null && existing.parent === parent) return

                val overlay = IOSNotificationShadeView(parent.context)
                overlay.tag = ModuleConfig.TAG_OVERLAY
                overlay.visibility = View.GONE

                parent.addView(
                    overlay,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                overlayRef = WeakReference(overlay)
                rootRef = WeakReference(parent)

                // Insets: apply once now (in case the listener never fires) and keep listening.
                parent.rootWindowInsets?.let { overlay.applyWindowInsets(it) }
                overlay.setOnApplyWindowInsetsListener { _, insets ->
                    // Do NOT consume: stock SystemUI children must keep receiving insets.
                    overlay.applyWindowInsets(insets)
                    insets
                }
                parent.post {
                    try {
                        parent.rootWindowInsets?.let { overlay.applyWindowInsets(it) }
                    } catch (t: Throwable) {
                        Log.e("deferred insets apply failed", t)
                    }
                }

                ShadeEventBus.register(this)
                dirty = true
                Log.i("Overlay attached to ${parent.javaClass.name}")

                // Catch up with whatever state the panel is already in.
                update(ShadeEventBus.lastFraction, ShadeEventBus.lastState)
            }
        } catch (t: Throwable) {
            Log.e("ShadeOverlayController.attach failed", t)
        }
    }

    // ------------------------------------------------------------------
    // ShadeEventBus.Listener (always invoked on the main thread)
    // ------------------------------------------------------------------
    override fun onExpandedFractionChanged(fraction: Float) {
        update(fraction, ShadeEventBus.lastState)
    }

    override fun onStatusBarStateChanged(state: Int) {
        update(ShadeEventBus.lastFraction, state)
    }

    override fun onNotificationsChanged() {
        dirty = true
        update(ShadeEventBus.lastFraction, ShadeEventBus.lastState)
    }

    /** Used by the overlay's Close button / tap-to-close. Animated. */
    fun forceHide() {
        mainHandler.post {
            try {
                val overlay = overlayRef?.get() ?: return@post
                if (!shown && overlay.visibility == View.GONE) return@post
                hideOverlay(overlay, animated = true)
            } catch (t: Throwable) {
                Log.e("forceHide failed", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // Show / hide decision
    // ------------------------------------------------------------------
    private fun update(fraction: Float, state: Int) {
        try {
            val overlay = overlayRef?.get() ?: return

            // Control Center (right-side pull) must stay 100% stock: our custom
            // notification panel must NOT open over quick settings. The guard
            // hook flags such gestures at the very first ACTION_DOWN.
            if (ShadeEventBus.controlCenterGesture) {
                if (shown || overlay.visibility != View.GONE) {
                    hideOverlay(overlay, animated = false)
                }
                return
            }
            // A fully closed panel means the next pull can come from either side.
            if (fraction < ModuleConfig.HIDE_FRACTION) {
                ShadeEventBus.controlCenterGesture = false
            }

            val onKeyguard = state != ShadeEventBus.STATE_SHADE
            keyguard = onKeyguard

            // EARLY suppression: hide the stock stack from the first pixel of
            // the pull (before our overlay is visible). Otherwise Samsung's stock
            // shade is visible and "pops up" during the handoff.
            if (!onKeyguard && fraction > 0.01f && hiddenStock.isEmpty()) {
                hideStockStack()
            } else if (fraction <= 0.001f && hiddenStock.isNotEmpty() && !shown) {
                restoreStockStack()
            }

            // Hysteresis: while shown, only a real collapse (fraction under the
            // lower threshold) hides us - One UI's settle bounce hovers just
            // under the show threshold and would otherwise flicker.
            val threshold =
                if (shown) ModuleConfig.HIDE_FRACTION else ModuleConfig.MIN_SHOW_FRACTION
            val shouldShow =
                fraction >= threshold &&
                    (!onKeyguard || ModuleConfig.SHOW_ON_KEYGUARD)

            if (!shouldShow) {
                if (shown || overlay.visibility != View.GONE) {
                    // If our settle animation is still running, DON'T hide now -
                    // the bus fraction drops faster than our visual sheet, and a
                    // premature hide skips the close animation ("closes instantly").
                    // onSettleEnd() will hide once the sheet is really off-screen.
                    if (overlay.isMotionAnimating()) return
                    hideOverlay(overlay, animated = true)
                } else if (hiddenStock.isNotEmpty()) {
                    // A previous hide may have been interrupted mid-flight.
                    restoreStockStack()
                }
                return
            }

            // Maintenance while visible: One UI occasionally flips stack visibility
            // during its own animations - put it straight back down, keep Samsung's
            // own clock revealed, the wallpaper PLAIN (no frost), and the content
            // glued to the pull.
            if (shown) {
                reassertHidden()
                killShadeBlur()
                overlay.setPanelState(fraction, onKeyguard)
            }

            // Render only when something changed or we were hidden (cheap at 120 Hz).
            if (dirty || !shown) {
                val models = NotifCollector.snapshot(forKeyguard = onKeyguard)
                // animateIn only on the transition into visibility, not on data
                // refreshes while the panel is already open.
                val hasContent = overlay.render(models, animateIn = !shown)
                dirty = false

                if (!hasContent) {
                    // Nothing to display -> keep stock One UI shade fully usable.
                    shown = false
                    overlay.visibility = View.GONE
                    overlay.isClickable = false
                    overlay.isFocusable = false
                    restoreStockStack()
                    restoreClockLayers()
                            return
                }
            }

            if (!shown) {
                // Cancel any in-flight HIDE before re-showing. Otherwise the
                // hide's end-action can fire mid-open and snap us back to GONE,
                // briefly exposing the stock Samsung notification panel.
                hiding = false
                overlay.cancelHideAnimation()
                // Hide the stock stack FIRST, before our overlay becomes visible,
                // so the stock panel never flashes through during the handoff.
                hideStockStack()
                killShadeBlur()
                // Re-assert z-order: SystemUI keeps adding windows (keyguard, bouncer...).
                rootRef?.get()?.bringChildToFront(overlay)
                // Lift above every sibling subtree so nothing stock can draw over us.
                try {
                    overlay.translationZ = 8f * overlay.resources.displayMetrics.density
                } catch (_: Throwable) {
                }
                overlay.setPanelState(fraction, onKeyguard)
                revealClockLayer()
                overlay.isClickable = true
                overlay.isFocusable = true
                overlay.animateShow()
                mainHandler.removeCallbacks(suppressionTick)
                startSuppressionTick()
                shown = true
                Log.i("Overlay shown (fraction=$fraction, state=$state)")
            }
        } catch (t: Throwable) {
            Log.e("ShadeOverlayController.update failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Show / hide helpers
    // ------------------------------------------------------------------
    private fun hideOverlay(overlay: IOSNotificationShadeView, animated: Boolean) {
        // Fraction events keep arriving while we animate out; ignore them.
        if (hiding) return
        try {
            // Keep `shown` TRUE (and stock suppressed) until the fade completes.
            // Restoring stock at the start let Samsung's stack flash in while our
            // sheet was still settling -> "original shade pops up after close".
            hiding = true
            mainHandler.removeCallbacks(suppressionTick)
            overlay.isClickable = false
            overlay.isFocusable = false

            // Explicitly collapse Samsung's panel ONCE. Our overlay follows the
            // finger visually, but if we don't tell the stock controller to close,
            // it stays expanded and its (restored) shade pops up after we hide.
            // A single call - not per-frame - so no feedback loop.
            try {
                PanelExpansionHook.requestCollapse()
            } catch (_: Throwable) {
            }

            if (animated) {
                overlay.animateHide {
                    shown = false
                    restoreStockStack()
                    restoreClockLayers()
                    hiding = false
                }
            } else {
                shown = false
                restoreStockStack()
                restoreClockLayers()
                overlay.visibility = View.GONE
                hiding = false
            }
            Log.i("Overlay hidden")
        } catch (t: Throwable) {
            // Last-resort fallback: snap everything back to a sane stock state.
            try {
                overlay.visibility = View.GONE
            } catch (_: Throwable) {
            }
            shown = false
            hiding = false
            mainHandler.removeCallbacks(suppressionTick)
            restoreStockStack()
            restoreClockLayers()
            Log.e("hideOverlay failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Wallpaper frost killer (the user wants NO blur behind the panel)
    // ------------------------------------------------------------------

    /**
     * One UI draws a frosted blur BEHIND the shade window (blur-behind radius
     * animated with the pull). The user wants the plain wallpaper behind the
     * notifications, so while our panel is up the blur-behind flag is removed
     * and the radius zeroed. Samsung re-sets its window params on its own
     * (state changes, new pulls) - we simply re-kill on every event while shown.
     */
    private var blurKilledLogged = false

    private fun killShadeBlur() {
        try {
            val root = rootRef?.get() ?: return
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
            var changed = false
            if (lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                changed = true
            }
            if (lp.blurBehindRadius != 0) {
                lp.blurBehindRadius = 0
                changed = true
            }
            if (changed) {
                val wm = root.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.updateViewLayout(root, lp)
                if (!blurKilledLogged) {
                    blurKilledLogged = true
                    Log.i("Shade blur-behind killed")
                }
            }
        } catch (t: Throwable) {
            Log.e("killShadeBlur failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Stock shade suppression (three layers, tag-based, never fatal)
    // ------------------------------------------------------------------

    /** Bounded DFS from the window root: matches the stack/footer by class name or resource id. */
    private fun hideStockStack() {
        try {
            val searchFrom = rootRef?.get()?.rootView ?: return
            if (ModuleConfig.DUMP_WINDOW_VIEWS) dumpWindowViews()
            collectStockStacks(searchFrom, 0)
            var pinned = 0
            for (ref in hiddenStock) {
                val v = ref.get() ?: continue
                if (v.visibility != View.INVISIBLE) {
                    v.visibility = View.INVISIBLE
                    pinned++
                }
            }
            if (hiddenStock.isNotEmpty()) {
                val names = hiddenStock.mapNotNull { it.get()?.javaClass?.simpleName }.distinct()
                Log.i("Stock shade hidden (${hiddenStock.size} view(s), $pinned fresh): $names")
            }
        } catch (t: Throwable) {
            Log.e("hideStockStack failed", t)
        }
    }

    /**
     * TEMPORARY diagnostic (ModuleConfig.DUMP_WINDOW_VIEWS): enumerates every root
     * view in the process (all windows) and logs clock/keyguard-ish classes, so the
     * ghost lock-clock can be found and pinned by its real class name.
     */
    private fun dumpWindowViews() {
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null) ?: return
            val views = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as? List<*> ?: return
            val params = wmg.getDeclaredField("mParams").apply { isAccessible = true }.get(inst) as? List<*> ?: return
            for (i in views.indices) {
                val v = views[i] as? View ?: continue
                val p = params.getOrNull(i) as? WindowManager.LayoutParams
                Log.i("WIN[$i] type=${p?.type} title=${p?.title} root=${v.javaClass.name}")
                if (p?.type == 2040) {
                    Log.i(
                        "SHADE WINDOW params: flags=${p.flags} blurBehindRadius=${p.blurBehindRadius} " +
                            "dimAmount=${p.dimAmount} alpha=${p.alpha} layoutInScreen=${p.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0}"
                    )
                }
                if (v is ViewGroup) dumpTree(v, 0)
            }
        } catch (t: Throwable) {
            Log.e("dumpWindowViews failed", t)
        }
    }

    private fun dumpTree(view: View, depth: Int) {
        if (depth > 18) return
        try {
            val n = view.javaClass.name
            val idName = try {
                if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
            } catch (_: Throwable) {
                ""
            }
            val hitsClass = n.contains("lock", ignoreCase = true) ||
                n.contains("Keyguard") ||
                n.contains("Compose") ||
                n.contains("clock", ignoreCase = true) ||
                n.contains("date", ignoreCase = true) ||
                n.contains("header", ignoreCase = true) ||
                n.contains("status", ignoreCase = true) ||
                n.contains("carrier", ignoreCase = true) ||
                n.contains("blur", ignoreCase = true) ||
                n.contains("scrim", ignoreCase = true) ||
                n.contains("dim", ignoreCase = true) ||
                n.contains("battery", ignoreCase = true) ||
                n.contains("signal", ignoreCase = true) ||
                n.contains("time", ignoreCase = true)
            val hitsId = idName.contains("clock", ignoreCase = true) ||
                idName.contains("date", ignoreCase = true) ||
                idName.contains("header", ignoreCase = true) ||
                idName.contains("status", ignoreCase = true) ||
                idName.contains("blur", ignoreCase = true) ||
                idName.contains("scrim", ignoreCase = true) ||
                idName.contains("dim", ignoreCase = true)
            if (hitsClass || hitsId) {
                Log.i("  TREE[${".".repeat(depth)}] $n id=$idName vis=${view.visibility} bounds=${try { view.width } catch (_: Throwable) { -1 }}x${try { view.height } catch (_: Throwable) { -1 }}")
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) dumpTree(view.getChildAt(i), depth + 1)
            }
        } catch (_: Throwable) {
        }
    }

    /** Bounded DFS: matches the stack/footer by class name or resource id. */
    private fun collectStockStacks(view: View, depth: Int) {
        if (depth > 32) return
        try {
            if (view.tag != TAG_STOCK_HIDDEN && isStockShadeView(view)) {
                view.tag = TAG_STOCK_HIDDEN
                hiddenStock.add(WeakReference(view))
                return // no need to descend into the stack itself
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collectStockStacks(view.getChildAt(i), depth + 1)
                }
            }
        } catch (t: Throwable) {
            // A single hostile view must not abort the traversal.
        }
    }

    private fun isStockShadeView(view: View): Boolean {
        try {
            val name = view.javaClass.name
            if (name.contains("NotificationStackScrollLayout") || name.contains("FooterView")) return true
            // Samsung's shade-header clock (QSClockHeaderView + its pop-over): part
            // of the shade chrome. Deliberately NOT matching plain TextClock.
            if (name.contains("QSClock")) return true
            // The lock-clock layer (lock_clock_blur / SecKeyguardStatusView /
            // SecKeyguardClockContainer) is NOT pinned - the ORIGINAL Samsung
            // clock is revealed & recolored by our overlay instead (see below).
            if (view.id != View.NO_ID) {
                val id = view.resources.getResourceEntryName(view.id)
                if (id == "notification_stack_scroller" || id == "notification_footer") return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    /** Called by ShadeMotion when a close settle finishes: now hide the overlay. */
    fun onSettledClosed() {
        mainHandler.post {
            try {
                val overlay = overlayRef?.get() ?: return@post
                if (shown || overlay.visibility != View.GONE) {
                    hideOverlay(overlay, animated = false)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** Current absolute sheet offset in px (for the touch hook's DOWN baseline). */
    fun currentOffsetPx(): Float = overlayRef?.get()?.currentOffsetPx() ?: 0f

    /** Forward a finger release (position + velocity) so the sheet settles. */
    fun onFingerRelease(offsetPx: Float, velocity: Float) {
        try {
            overlayRef?.get()?.onFingerRelease(offsetPx, velocity)
        } catch (_: Throwable) {
        }
    }

    /**
     * Called SYNCHRONOUSLY from the touch hook on ACTION_DOWN. Hide stock at
     * the very first pixel so Samsung's shade can never flash in, and force-hide
     * immediately for a Control Center (right-side) pull.
     */
    fun onGestureDown(rightSide: Boolean) {
        try {
            if (rightSide) {
                forceHide()
                return
            }
            // Suppress stock synchronously (only hide the stack; do NOT reparent
            // the clock - that happens when the overlay actually shows).
            hideStockStack()
        } catch (_: Throwable) {
        }
    }

    /** Synchronous 1:1 finger tracking - no event-bus frame lag. */
    fun onGestureMove(offsetPx: Float) {
        try {
            overlayRef?.get()?.onFingerMove(offsetPx)
        } catch (_: Throwable) {
        }
    }

    /** Called on ACTION_UP; the settle was already started by onFingerRelease. */
    fun onGestureUp() {
        // Intentionally empty.
    }

    /**
     * The user wants the ORIGINAL Samsung lock-screen clock. One UI's shade
     * clock layer (lock_clock_blur -> SecKeyguardStatusView -> SecKeyguardClock
     * Container -> live TextClocks) is revealed, with its blur backdrop
     * stripped. One UI keeps re-hiding the clock container, so ClockForceHook
     * pins it VISIBLE while our panel is up. Restored exactly on hide.
     */
    private class ClockLayerEntry(
        val view: WeakReference<View>,
        val visibility: Int,
        val background: Drawable?,
        val alpha: Float,
        val translationY: Float,
    )

    private val clockLayers = ArrayList<ClockLayerEntry>()

    /** The top clock layer view, moved into our panel (above wallpaper, below cards). */
    private class MovedClockLayer(
        val view: WeakReference<View>,
        val parent: WeakReference<ViewGroup>,
        val index: Int,
    )

    private var movedClockLayer: MovedClockLayer? = null
    private var clockTreeDumped = false

    private fun dumpClockTree(view: View, depth: Int) {
        if (depth > 12) return
        try {
            val n = view.javaClass.name
            val idName = try {
                if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
            } catch (_: Throwable) {
                ""
            }
            Log.i(
                "CLOCK-TREE[${".".repeat(depth)}] $n id=$idName vis=${view.visibility} " +
                    "alpha=${view.alpha} bounds=${view.width}x${view.height}@${view.top},${view.left}"
            )
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) dumpClockTree(view.getChildAt(i), depth + 1)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isClockLayer(view: View): Boolean = try {
        val name = view.javaClass.name
        name.contains("SecKeyguardStatusView") ||
            name.contains("SecKeyguardClockContainer") ||
            name.contains("DigitalHorizontalClockView") ||
            name.contains("ClockNonePaddingTextView") ||
            name.contains("ClockTextView")
    } catch (_: Throwable) {
        false
    }

    /** Finds the real clock container (SecKeyguardStatusView), not the blur layer. */
    private fun findTopClockLayer(view: View, depth: Int): View? {
        if (depth > 32) return null
        try {
            if (view.javaClass.name.contains("SecKeyguardStatusView")) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = findTopClockLayer(view.getChildAt(i), depth + 1)
                    if (found != null) return found
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun revealClockLayer() {
        try {
            val overlay = overlayRef?.get() ?: return
            if (movedClockLayer != null) {
                // Already inside our panel - just re-pin visibility (One UI keeps
                // trying to hide its clock views).
                for (entry in clockLayers) {
                    val v = entry.view.get() ?: continue
                    if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                    // Samsung animates this view to alpha 0 over home - force it
                    // opaque while our panel is up, otherwise the clock is invisible.
                    if (v.alpha < 1f) v.alpha = 1f
                }
                return
            }
            val root = rootRef?.get()?.rootView ?: return
            val layer = findTopClockLayer(root, 0) ?: return
            val parent = layer.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(layer)

            // Remember every clock-layer view (visibility + original background)
            // and strip the blurred lock-wallpaper backdrop.
            collectClockLayers(layer, 0)

            parent.removeView(layer)
            // Insert inside the clipped sheet: above wallpaper, below cards.
            overlay.addClockLayer(layer)
            movedClockLayer = MovedClockLayer(WeakReference(layer), WeakReference(parent), index)

            for (entry in clockLayers) {
                val v = entry.view.get() ?: continue
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                if (v.alpha < 1f) v.alpha = 1f
            }
            // Hand the clock layer to the view so ALL its motion comes from the
            // single authoritative expansion (no hard-coded one-shot lift).
            overlayRef?.get()?.setClockLayer(layer)
        } catch (t: Throwable) {
            Log.e("revealClockLayer failed", t)
        }
    }

    private fun collectClockLayers(view: View, depth: Int) {
        if (depth > 32) return
        try {
            if (isClockLayer(view) && clockLayers.none { it.view.get() === view }) {
                clockLayers.add(
                    ClockLayerEntry(
                        WeakReference(view), view.visibility, view.background,
                        view.alpha, view.translationY
                    )
                )
                view.alpha = 1f
                try {
                    view.background = null // plain wallpaper, no blur backdrop
                    view.setRenderEffect(null)
                } catch (_: Throwable) {
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collectClockLayers(view.getChildAt(i), depth + 1)
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreClockLayers() {
        overlayRef?.get()?.setClockLayer(null)
        val moved = movedClockLayer
        movedClockLayer = null
        if (moved != null) {
            val view = moved.view.get()
            val parent = moved.parent.get()
            try {
                (view?.parent as? ViewGroup)?.removeView(view)
                if (view != null && parent != null) {
                    parent.addView(view, moved.index.coerceAtMost(parent.childCount))
                }
            } catch (t: Throwable) {
                Log.e("restoreClockLayer reparent failed", t)
            }
        }
        for (entry in clockLayers) {
            val v = entry.view.get() ?: continue
            try {
                v.visibility = entry.visibility
                v.background = entry.background
                v.alpha = entry.alpha
                v.translationY = entry.translationY
            } catch (_: Throwable) {
            }
        }
        clockLayers.clear()
    }

    private fun reassertHidden() {
        try {
            for (ref in hiddenStock) {
                val v = ref.get() ?: continue
                if (v.tag == TAG_STOCK_HIDDEN && v.visibility != View.INVISIBLE) {
                    v.visibility = View.INVISIBLE
                }
            }
            revealClockLayer()
        } catch (t: Throwable) {
            Log.e("reassertHidden failed", t)
        }
    }

    private fun restoreStockStack() {
        if (hiddenStock.isEmpty()) return
        try {
            for (ref in hiddenStock) {
                val v = ref.get() ?: continue
                if (v.tag == TAG_STOCK_HIDDEN) {
                    v.tag = null
                    v.visibility = View.VISIBLE
                }
            }
        } catch (t: Throwable) {
            Log.e("restoreStockStack failed", t)
        } finally {
            hiddenStock.clear()
        }
    }
}
