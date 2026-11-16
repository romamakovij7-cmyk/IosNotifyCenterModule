package com.iosshade.systemui.overlay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.ModuleConfig
import com.iosshade.systemui.core.ShadeEventBus
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

            val onKeyguard = state != ShadeEventBus.STATE_SHADE
            keyguard = onKeyguard
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
                    hideOverlay(overlay, animated = true)
                } else if (hiddenStock.isNotEmpty()) {
                    // A previous hide may have been interrupted mid-flight.
                    restoreStockStack()
                }
                return
            }

            // Maintenance while visible: One UI occasionally flips stack visibility
            // during its own animations - put it straight back down, and keep the
            // backdrop in sync with the pull (liquid glass -> wallpaper).
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
                    return
                }
            }

            if (!shown) {
                hiding = false
                // Re-assert z-order: SystemUI keeps adding windows (keyguard, bouncer...).
                rootRef?.get()?.bringChildToFront(overlay)
                // Lift above every sibling subtree so nothing stock can draw over us.
                try {
                    overlay.translationZ = 8f * overlay.resources.displayMetrics.density
                } catch (_: Throwable) {
                }
                overlay.setPanelState(fraction, onKeyguard)
                hideStockStack()
                overlay.isClickable = true
                overlay.isFocusable = true
                overlay.animateShow()
                shown = true
                startSuppressionTick()
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
            // `shown` must go false BEFORE restoring: the suppressor hook keys off it.
            hiding = true
            shown = false
            mainHandler.removeCallbacks(suppressionTick)
            overlay.isClickable = false
            overlay.isFocusable = false

            // Bring the stock stack back immediately: our backdrop fades out on top
            // of it, so the cross-fade reads as one dissolving panel instead of an
            // empty shade that pops its content in later.
            restoreStockStack()

            if (animated) {
                overlay.animateHide { hiding = false }
            } else {
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
            Log.e("hideOverlay failed", t)
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
                n.contains("carrier", ignoreCase = true)
            val hitsId = idName.contains("clock", ignoreCase = true) ||
                idName.contains("date", ignoreCase = true) ||
                idName.contains("header", ignoreCase = true) ||
                idName.contains("status", ignoreCase = true)
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
            // Samsung's shade-header clock (QSClockHeaderView + its pop-over) ghosts
            // through our backdrop at the top-left. Deliberately NOT matching plain
            // TextClock - our own clock is one.
            if (name.contains("QSClock")) return true
            // The BIG ghost: Samsung's full-screen lock-clock layer (id lock_clock_blur,
            // SecKeyguardClockContainer with two bare TextClocks) - One UI shows it
            // above the shade even over the home screen.
            if (name.contains("SecKeyguardClockContainer") || name.contains("SecKeyguardStatusView")) return true
            if (view.id != View.NO_ID) {
                val id = view.resources.getResourceEntryName(view.id)
                if (id == "notification_stack_scroller" || id == "notification_footer") return true
                if (id == "lock_clock_blur") return true
            }
            // On the lock screen, Samsung's own clock/date/shortcuts live in the
            // keyguard root (Compose-based on One UI 9) - they ghost through our
            // translucent backdrop, so they go too while our panel is up.
            if (keyguard && name.contains("KeyguardRootView")) return true
        } catch (_: Throwable) {
        }
        return false
    }

    /** Layer 3: re-assert on every shade event while our overlay is visible. */
    private fun reassertHidden() {
        try {
            for (ref in hiddenStock) {
                val v = ref.get() ?: continue
                if (v.tag == TAG_STOCK_HIDDEN && v.visibility != View.INVISIBLE) {
                    v.visibility = View.INVISIBLE
                }
            }
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
