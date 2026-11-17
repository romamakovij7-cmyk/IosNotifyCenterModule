package com.iosshade.systemui.overlay

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

    /**
     * Persistent identity cache of stock stack/footer views found by the DFS.
     * Populated ONCE per SystemUI lifetime so repeat opens skip the tree scan
     * (lifecycle: avoid re-discovering / re-creating work on every pull).
     */
    private val cachedStockViews = ArrayList<WeakReference<View>>()
    private var stockScanned = false

    /**
     * Samsung chrome that must disappear while our shade is up:
     *  - CapturedBlurContainer / lock_clock_blur  -> the frosted blur
     *  - SecPanelBackground / fake_wallpaper      -> One UI's own background
     *  - QSClockHeaderView / QSShortenDate        -> duplicate status bar + date
     * Each entry remembers its original visibility so hide() restores exactly.
     */
    private data class ChromeEntry(val ref: WeakReference<View>, val visibility: Int)
    private val hiddenChrome = ArrayList<ChromeEntry>()
    private var chromeCollected = false

    /** Pending "restore stock after collapse" callback; cancelled if we re-open. */
    private var restoreRunnable: Runnable? = null

    /**
     * True from ACTION_DOWN until the first fraction event that commits a show.
     * The sheet is made visible instantly on DOWN (for a responsive first frame);
     * this guard stops update() from hiding it again at fraction≈0 before the
     * finger has actually moved.
     */
    private var gestureStarting = false

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
                reassertChromeHidden()
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
                if (cachedStockViews.none { it.get() === view }) {
                    cachedStockViews.add(WeakReference(view))
                }
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
                // New root (SystemUI recreated): invalidate view-discovery caches.
                cachedStockViews.clear()
                stockScanned = false
                hiddenStock.clear()
                hiddenChrome.clear()
                chromeCollected = false

                // Pre-warm the wallpaper drawable once at attach (never during a
                // pull) so the first gesture frame never blocks on WallpaperManager.
                overlay.prewarmWallpaper()

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
        // Pre-render cards while hidden so the first visible frame has no view
        // inflation / icon loading to do (off the gesture critical path).
        if (!shown && ShadeEventBus.lastState == ShadeEventBus.STATE_SHADE) {
            overlayRef?.get()?.prepareContent(NotifCollector.snapshot(forKeyguard = false))
        }
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

            // The keyguard / PIN screen shares the SAME window and several of the
            // same views we suppress (captured_blur_container, lock_clock_blur,
            // header_clock, ...). The instant the state leaves SHADE we must hand
            // ALL of them back, otherwise the PIN screen inherits hidden blur
            // views and renders透明 / transparent. Never suppress on keyguard.
            if (onKeyguard) {
                restoreStockStack()
                restoreClockLayers()
                mainHandler.removeCallbacks(suppressionTick)
                if (shown || overlay.visibility != View.GONE) {
                    overlay.animate().cancel()
                    overlay.visibility = View.GONE
                    shown = false
                }
                hiding = false
                restoreRunnable?.let { mainHandler.removeCallbacks(it) }
                restoreRunnable = null
                return
            }

            // CONTINUOUS suppression: from the very first pixel of the pull until
            // the panel closes, keep Samsung's stack/chrome pinned. A one-shot
            // hide let One UI's layout passes re-show the stock shade mid-pull
            // ("original notification center opens again").
            if (!onKeyguard && fraction > 0.01f) {
                if (hiddenStock.isEmpty()) hideStockStack() else pinStockViews()
                if (!chromeCollected) collectChromeOnce() else reassertChromeHidden()
            } else if (fraction <= 0.001f && hiddenStock.isNotEmpty() &&
                !shown && restoreRunnable == null
            ) {
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

            // Re-opened while a close-restore was still pending: cancel it so we
            // don't get yanked back to GONE by a stale restore callback.
            if (shouldShow && hiding) {
                restoreRunnable?.let { mainHandler.removeCallbacks(it) }
                restoreRunnable = null
                hiding = false
                shown = false
            }

            if (!shouldShow) {
                // A pull just started: the sheet was made visible instantly on
                // ACTION_DOWN but fraction is still ~0. Don't yank it back; wait
                // for the finger to either commit (fraction rises) or release.
                if (gestureStarting && !shown && !overlay.isMotionAnimating()) return
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

            // A real pull has committed — the gesture-start grace period is over.
            gestureStarting = false

            // Maintenance while visible: One UI occasionally flips stack visibility
            // during its own animations - put it straight back down, keep Samsung's
            // own clock revealed, the wallpaper PLAIN (no frost), and the content
            // glued to the pull.
            if (shown) {
                reassertHidden()
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
                // warmUp() (on ACTION_DOWN) already rendered the cards and cached
                // the wallpaper, so render() now hits the key-match fast path and
                // animateShow() is reached quickly. Hide stock before our sheet
                // becomes visible so it can never flash through the handoff.
                hideStockStack()
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
            // CRITICAL: keep `shown` TRUE (and stock suppressed) until Samsung's
            // panel is CONFIRMED collapsed. Restoring stock the instant our sheet
            // disappears is exactly why the original shade "pops up again".
            hiding = true
            mainHandler.removeCallbacks(suppressionTick)
            overlay.isClickable = false
            overlay.isFocusable = false

            // Ask Samsung to collapse once; the delayed restore below re-checks
            // and re-issues if the panel is still expanded.
            try {
                PanelExpansionHook.requestCollapse()
            } catch (_: Throwable) {
            }

            if (animated) {
                overlay.animateHide { scheduleStockRestore() }
            } else {
                overlay.animate().cancel()
                overlay.visibility = View.GONE
                overlay.alpha = 1f
                overlay.translationY = 0f
                overlay.scaleX = 1f
                overlay.scaleY = 1f
                scheduleStockRestore()
            }
            Log.i("Overlay hidden (visual)")
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

    /**
     * Restores stock only after the panel's fraction has actually reached 0.
     * Until then Samsung stays suppressed (no "original shade reopened" flash).
     */
    private fun scheduleStockRestore() {
        restoreRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                try {
                    if (ShadeEventBus.lastFraction > 0.04f) {
                        // Still expanded: keep suppressed and collapse again.
                        try { PanelExpansionHook.requestCollapse() } catch (_: Throwable) {}
                        mainHandler.postDelayed(this, 80)
                        return
                    }
                    restoreRunnable = null
                    shown = false
                    restoreStockStack()
                    restoreClockLayers()
                    hiding = false
                    Log.i("Stock restored after confirmed collapse")
                } catch (t: Throwable) {
                    restoreRunnable = null
                    shown = false
                    restoreStockStack()
                    restoreClockLayers()
                    hiding = false
                    Log.e("scheduleStockRestore failed", t)
                }
            }
        }
        restoreRunnable = r
        mainHandler.postDelayed(r, 120)
    }

    // ------------------------------------------------------------------
    // NOTE: we intentionally do NOT touch the shade window's FLAG_BLUR_BEHIND
    // or blurBehindRadius. That window (type 2040) also hosts the keyguard
    // bouncer / PIN view (mBouncerParentView). Mutating its layout params made
    // the PIN screen lose its blurred backdrop and turn transparent on digit
    // entry. The plain-wallpaper look is achieved purely by hiding the in-view
    // CapturedBlurContainer (see chrome suppression), never the window flags.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Stock shade suppression (three layers, tag-based, never fatal)
    // ------------------------------------------------------------------

    /** Bounded DFS from the window root: matches the stack/footer by class name or resource id. */
    private fun hideStockStack() {
        try {
            // Scan the tree ONCE; reuse the identity cache on every later open.
            if (!stockScanned) {
                val searchFrom = rootRef?.get()?.rootView ?: return
                if (ModuleConfig.DUMP_WINDOW_VIEWS) dumpWindowViews()
                collectStockStacks(searchFrom, 0)
                stockScanned = true
            }
            // Rebuild the active suppression set from the persistent cache,
            // skipping views that have since been GC'd / detached.
            if (hiddenStock.isEmpty()) {
                for (ref in cachedStockViews) {
                    ref.get()?.let { hiddenStock.add(WeakReference(it)) }
                }
            }
            collectChromeOnce()
            reassertChromeHidden()
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
            if (isStockShadeView(view) &&
                cachedStockViews.none { it.get() === view }
            ) {
                cachedStockViews.add(WeakReference(view))
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

    // ------------------------------------------------------------------
    // Chrome suppression: remove One UI's blur layer, its own background and
    // the duplicate status-bar/date views while our shade is visible.
    // ------------------------------------------------------------------

    private fun isChromeView(view: View): Boolean {
        try {
            val id = if (view.id != View.NO_ID) {
                try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
            } else ""
            val cls = view.javaClass.name
            if (id == "captured_blur_container" || id == "lock_clock_blur" ||
                id == "nowbrief_lock_blur" || id == "patial_blur_view" ||
                id == "notification_panel_background" || id == "fake_wallpaper" ||
                id == "header_clock" || id == "header_date" ||
                id == "pop_over_immersive_clock"
            ) return true
            if (cls.contains("CapturedBlurContainer") ||
                cls.contains("QSClockHeaderView") ||
                cls.contains("QSShortenDate") ||
                cls.contains("QSClockPopOverImmersiveView") ||
                cls.contains("SecPanelBackground")
            ) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private fun collectChrome(view: View, depth: Int) {
        if (depth > 32) return
        try {
            if (isChromeView(view) && hiddenChrome.none { it.ref.get() === view }) {
                hiddenChrome.add(ChromeEntry(WeakReference(view), view.visibility))
                view.visibility = View.INVISIBLE
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collectChrome(view.getChildAt(i), depth + 1)
            }
        } catch (_: Throwable) {
        }
    }

    private fun collectChromeOnce() {
        if (chromeCollected && hiddenChrome.isNotEmpty()) return
        val root = rootRef?.get()?.rootView ?: return
        collectChrome(root, 0)
        chromeCollected = true
    }

    private fun reassertChromeHidden() {
        try {
            for (e in hiddenChrome) {
                val v = e.ref.get() ?: continue
                if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreChrome() {
        try {
            for (e in hiddenChrome) {
                e.ref.get()?.visibility = e.visibility
            }
        } catch (_: Throwable) {
        }
        hiddenChrome.clear()
        chromeCollected = false
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
            // NEVER suppress stock while the keyguard / PIN is up: the PIN screen
            // shares the shade window's blur and clock views. Tapping a PIN digit
            // reaches this hook (shade window dispatchTouchEvent); hiding stock
            // there made the PIN backdrop transparent.
            if (keyguard || ShadeEventBus.lastState != ShadeEventBus.STATE_SHADE) return

            // Make the already-warmed sheet visible on ACTION_DOWN (cheap: no DFS,
            // no icon loading) so the FIRST move paints immediately. Content was
            // pre-rendered on notification changes; wallpaper at attach.
            val overlay = overlayRef?.get() ?: return
            gestureStarting = true
            overlay.showInstantly()
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
        // A tap that never committed a pull leaves the sheet visible-but-parked
        // from showInstantly(); hand it back to stock cleanly.
        mainHandler.post {
            try {
                if (gestureStarting && !shown) {
                    gestureStarting = false
                    val overlay = overlayRef?.get() ?: return@post
                    if (overlay.visibility != View.GONE && !overlay.isMotionAnimating()) {
                        overlay.animate().cancel()
                        overlay.visibility = View.GONE
                    }
                    restoreStockStack()
                }
            } catch (_: Throwable) {
            }
        }
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
                    // Do NOT force alpha here: IOSNotificationShadeView.applyExpansion
                    // drives the clock alpha for the unlock-style exit animation.
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

    /** Cheap pin of already-known stock views (no clock reparent / no DFS). */
    private fun pinStockViews() {
        try {
            for (ref in hiddenStock) {
                val v = ref.get() ?: continue
                if (v.tag == TAG_STOCK_HIDDEN && v.visibility != View.INVISIBLE) {
                    v.visibility = View.INVISIBLE
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun reassertHidden() {
        pinStockViews()
        try {
            revealClockLayer()
        } catch (t: Throwable) {
            Log.e("reassertHidden failed", t)
        }
    }

    private fun restoreStockStack() {
        restoreChrome()
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
