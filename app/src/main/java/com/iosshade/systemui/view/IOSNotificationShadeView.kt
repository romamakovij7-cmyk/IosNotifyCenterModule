package com.iosshade.systemui.view

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.data.NotifModel
import com.iosshade.systemui.hooks.PanelExpansionHook

/**
 * The notification shade, iOS style, ON THE PLAIN WALLPAPER (no keyguard UI,
 * no frost). The clock is SAMSUNG'S OWN ("my main" one, the user said): the
 * controller reveals One UI's shade-clock layer (lock_clock_blur) with its
 * blur backdrop stripped, and this view draws the notifications under it:
 *
 *   FrameLayout (fully transparent - the wallpaper shows through)
 *   ├─ ScrollView
 *   │  └─ LinearLayout (column, transparent)
 *   │     ├─ clock block:  date (small) + time (huge thin, centered)
 *   │     └─ NotifCardView * N   (collapsed: newest only; tap it to expand all)
 *   ├─ bottom row: [flashlight] ..... [camera]  (floating lock-screen buttons)
 *   └─ glass edge bar (visible ONLY while opening/closing)
 *
 * The clock is a system TextClock (self-updating, zero timers of our own).
 * Every animation degrades to an instant visibility flip if the animator fails.
 */
class IOSNotificationShadeView(context: android.content.Context) : FrameLayout(context) {

    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val cardsColumn = LinearLayout(context)
    private val wallpaper = android.widget.ImageView(context)
    private val edgeView = FrameLayout(context)
    /** The sheet itself: clipped to a rounded-bottom shape (the visible end). */
    private val sheet = FrameLayout(context)
    private val statusBar = StatusBarView(context)
    private var blurredWallpaper: android.graphics.Bitmap? = null
    private var torchButton: FrameLayout? = null
    private var torchOn = false
    private var lockButtonsRow: View? = null
    private var clockLayer: java.lang.ref.WeakReference<View>? = null
    private val edgeAnimating = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The single authoritative expansion (0..1) and all gesture physics.
     * Every visual element derives its transform from [applyExpansion].
     */
    private val motion: ShadeMotion = ShadeMotion(context, object : ShadeMotion.Host {
        override fun onExpansionChanged(expansion: Float, velocity: Float, tracking: Boolean) {
            applyExpansion(expansion, velocity, tracking)
        }

        override fun onDriveStock(fraction: Float) {
            // NO-OP: writing our fraction back into Samsung created a feedback
            // loop (it re-posted -> overlay re-showed -> "spawn" after fast
            // swipes). Samsung already moves its panel from the real touch; our
            // overlay only mirrors it visually.
        }

        override fun onSettleEnd(target: Float) {
            if (target <= 0.001f) {
                com.iosshade.systemui.overlay.ShadeOverlayController.onSettledClosed()
            }
        }

        override fun canScrollContentDown(): Boolean = scroll.canScrollVertically(1)

        override fun screenHeight(): Int = resources.displayMetrics.heightPixels
    })

    /**
     * Lock-screen behaviour: collapsed shows ONLY the newest notification;
     * tapping it reveals the full list. Reset on every hide.
     */
    private var expanded = false

    /** Last rendered snapshot - reused when the collapsed card expands. */
    private var lastModels: List<NotifModel> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending hide safety-net (cancelled whenever the panel is shown again). */
    private var hideSafety: Runnable? = null

    private val springInterpolator = OvershootInterpolator(0.8f)
    private val hideInterpolator = AccelerateInterpolator(1.2f)
    /** Smooth unlock-like reveal when the shade closes. */
    private val revealInterpolator = android.view.animation.DecelerateInterpolator(1.6f)
    private val fastOutSlowIn = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    init {
        clipToPadding = false
        clipChildren = false

        // The SHEET container: everything that belongs to the notification
        // center lives inside it. A rounded-bottom outline clips the wallpaper,
        // clock, cards and buttons so the whole sheet ends in a crisp round edge.
        try {
            sheet.clipToPadding = false
            sheet.clipChildren = true
            sheet.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val w = view.width
                    val h = view.height
                    if (w <= 0 || h <= 0) return
                    val r = dp(38)
                    // setRoundRect is GPU-accelerated (unlike a Path mask).
                    // Shift the rect UP by r so only the BOTTOM corners curve
                    // inside the view; the top stays square under the status bar.
                    outline.setRoundRect(0, -r, w, h, r.toFloat())
                }
            }
            sheet.clipToOutline = true
            addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } catch (t: Throwable) {
            Log.e("sheet init failed", t)
        }

        // The real wallpaper as the sheet's background.
        try {
            wallpaper.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            sheet.addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } catch (t: Throwable) {
            Log.e("wallpaper init failed", t)
        }

        // iOS-style status bar on the ROOT (not the sheet) so it stays pinned
        // at the top regardless of the sheet's finger-tracked translation.
        try {
            addView(
                statusBar,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(30), Gravity.TOP)
            )
        } catch (t: Throwable) {
            Log.e("statusBar init failed", t)
        }

        // Transparent backdrop: the wallpaper shows through fully (the user asked for
        // no frost/blur). A whisper of dim is added only while rolling (see
        // setPanelState) and fades to perfectly clear at full expansion.
        setBackgroundColor(0x00000000)

        // NOT fill-viewport: the sheet must wrap its content so the bottom edge
        // sits right below the notifications - the visible end of the shade.
        scroll.isFillViewport = false
        scroll.overScrollMode = ScrollView.OVER_SCROLL_NEVER
        // No visible scrollbar - iOS-style clean scrolling.
        scroll.isVerticalScrollBarEnabled = false
        scroll.isHorizontalScrollBarEnabled = false
        scroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scroll.addView(column, ViewGroupLayoutParams())
        sheet.addView(scroll)

        // The scroll consumes touches so they never pass through to apps behind
        // the shade, but a tap on EMPTY panel space must NOT dismiss the shade.
        // Dismissal is intentional only: swipe-up (ShadeMotion settle) or the
        // dedicated close action. Finger DRAG tracking is done synchronously by
        // ControlCenterGuardHook (window-level) - NOT here - single source of truth.
        scroll.isClickable = true

        // Transparent column: cards float directly over the plain wallpaper.
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16), 0, dp(16), dp(16))

        // Dedicated container for notification cards: render() only ever touches
        // this child, so nothing else in the hierarchy can ever be disturbed.
        cardsColumn.orientation = LinearLayout.VERTICAL
        // Cards start BELOW Samsung's own clock zone (the top ~43% of the
        // screen, where the revealed lock-screen clock lives).
        column.addView(
            cardsColumn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (resources.displayMetrics.heightPixels * 0.64f).toInt() }
        )

        buildLockButtons()
        buildEdge()

        // Default: inert. The controller toggles clickable only while visible.
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    // ------------------------------------------------------------------
    // The shade edge: a rounded GLASS bar (translucent gradient + hairline
    // rim) that marks where the shade ENDS. It rides the bottom of the
    // shade region and is visible ONLY while the shade is moving
    // (opening/closing) - it fades out the moment the panel settles.
    // ------------------------------------------------------------------
    private fun buildEdge() {
        try {
            edgeView.isClickable = false
            edgeView.isFocusable = false
            edgeView.alpha = 0f

            // A rounded white home-indicator (pill) marking the end of the shade.
            edgeView.removeAllViews()
            val line = View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt())
                    cornerRadius = dp(3).toFloat() // half the height => pill
                }
                alpha = 0.92f
            }
            edgeView.addView(
                line,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(6))
            )

            val lp = LayoutParams(dp(160), dp(6), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            lp.bottomMargin = dp(8)
            sheet.addView(edgeView, lp)
        } catch (t: Throwable) {
            Log.e("buildEdge failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Wallpaper: drawn SHARP so it covers One UI's frosted/blurred home layer.
    // No blur computation at all - the glass buttons use a clean translucent
    // tint (liquid glass postponed), so no background blur is produced by us.
    // ------------------------------------------------------------------
    private var cachedWallpaper: android.graphics.drawable.Drawable? = null

    private fun refreshWallpaper() {
        try {
            // Cache the drawable: querying WallpaperManager on every show was a
            // noticeable main-thread stall at the start of each pull.
            if (cachedWallpaper == null) {
                cachedWallpaper = android.app.WallpaperManager.getInstance(context).drawable
            }
            val drawable = cachedWallpaper ?: return
            if (wallpaper.drawable !== drawable) {
                wallpaper.setImageDrawable(drawable)
            }
            // Light dim only (no frost/blur). Re-applied once; setColorFilter is
            // cheap but we skip it when already set.
            wallpaper.setColorFilter(0x14000000, android.graphics.PorterDuff.Mode.SRC_OVER)
            blurredWallpaper = null
        } catch (t: Throwable) {
            Log.w("refreshWallpaper failed: ${t.message}")
        }
    }

    /** Samples the blurred wallpaper at this view's on-screen position. No allocation. */
    private inner class GlassBackdropView : View(context) {
        private val src = android.graphics.Rect()
        private val dst = android.graphics.RectF()
        private val loc = IntArray(2)

        override fun onDraw(canvas: android.graphics.Canvas) {
            try {
                val bmp = blurredWallpaper ?: return
                val rootW = rootView.width.coerceAtLeast(1)
                val rootH = rootView.height.coerceAtLeast(1)
                getLocationOnScreen(loc)
                val sx = bmp.width.toFloat() / rootW
                val sy = bmp.height.toFloat() / rootH
                val l = (loc[0] * sx).toInt().coerceAtLeast(0)
                val t = (loc[1] * sy).toInt().coerceAtLeast(0)
                val r = ((loc[0] + width) * sx).toInt().coerceAtMost(bmp.width)
                val b = ((loc[1] + height) * sy).toInt().coerceAtMost(bmp.height)
                if (r <= l || b <= t) return
                src.set(l, t, r, b)
                dst.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(bmp, src, dst, null)
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Bottom lock-screen buttons: flashlight + camera. They FLOAT at the
    // bottom of the revealed lock screen (over the wallpaper), like the
    // original lock-screen design - not inside the notification column.
    // ------------------------------------------------------------------
    private fun buildLockButtons() {
        try {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val torch = circleButton(GlyphView(context, GlyphView.KIND_FLASH, iconColor()))
            torch.setOnClickListener { toggleTorch() }
            torchButton = torch

            val camera = circleButton(GlyphView(context, GlyphView.KIND_CAMERA, iconColor()))

            camera.setOnClickListener { openCamera() }

            row.addView(torch, LinearLayout.LayoutParams(dp(52), dp(52)))
            row.addView(
                camera,
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(140) }
            )

            // Buttons are pinned to the ROOT bottom (not the dynamic-height
            // sheet), so they never overlap the notification card.
            addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                    .apply { bottomMargin = dp(34) }
            )
            lockButtonsRow = row
        } catch (t: Throwable) {
            Log.e("buildLockButtons failed", t)
        }
    }

    private fun circleButton(glyph: View): FrameLayout {
        return FrameLayout(context).apply {
            // Rounded outline: everything inside is clipped to the circle.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true

            // 1) Blurred piece of the wallpaper behind the button (real glass).
            addView(
                GlassBackdropView(),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            // 2) Translucent glass tint + hairline rim.
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        colors = intArrayOf(0x42FFFFFF, 0x1EFFFFFF)
                        setStroke(dp(1), 0x66FFFFFF)
                    }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            // 3) The glyph on top.
            addView(
                glyph,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
    }

    private fun toggleTorch() {
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            for (id in cm.cameraIdList) {
                val hasFlash =
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cm.setTorchMode(id, !torchOn)
                    torchOn = !torchOn
                    updateTorchUi()
                    return
                }
            }
            Log.w("toggleTorch: no flash unit found")
        } catch (t: Throwable) {
            // Torch is a bonus feature; camera busy or blocked = silently ignore.
            Log.w("toggleTorch failed: ${t.message}")
        }
    }

    private fun updateTorchUi() {
        try {
            torchButton?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (torchOn) 0x8AFFFFFF.toInt() else 0x47FFFFFF)
            }
        } catch (_: Throwable) {
        }
    }

    private fun openCamera() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            closePanel()
        } catch (t: Throwable) {
            Log.w("openCamera failed: ${t.message}")
            closePanel()
        }
    }

    private fun closePanel() {
        try {
            PanelExpansionHook.requestCollapse()
        } catch (t: Throwable) {
            Log.e("requestCollapse failed", t)
        }
        try {
            com.iosshade.systemui.overlay.ShadeOverlayController.forceHide()
        } catch (t: Throwable) {
            Log.e("forceHide failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Lock-screen behaviour: when collapsed (default after every hide) only the
     * NEWEST notification is on the sheet - tapping it reveals the whole list
     * (staggered entrance). Expanded, cards open their apps as usual.
     *
     * Returns true when there is content to display (the controller uses this
     * to fall back to the stock shade when empty).
     */
    fun render(models: List<NotifModel>, animateIn: Boolean): Boolean {
        return try {
            lastModels = models
            if (models.isEmpty()) {
                cardsColumn.removeAllViews()
                return false
            }

            val newest = models.maxByOrNull { it.timeMillis } ?: models.first()
            val visible = if (expanded) models else listOf(newest)

            // Reuse existing cards when the visible COUNT and keys match -
            // avoids re-inflating views / reloading icons on every re-open.
            val existing = (0 until cardsColumn.childCount).map { cardsColumn.getChildAt(it) }
            val keysMatch = existing.size == visible.size &&
                existing.indices.all { i ->
                    existing[i].tag as? String == visible[i].key
                }

            if (keysMatch) {
                if (animateIn) {
                    visible.indices.forEach { playCardEntrance(existing[it], it) }
                }
                scroll.scrollTo(0, 0)
                return true
            }

            cardsColumn.removeAllViews()
            val tapOverride: (() -> Unit)? = if (!expanded) ({ expandAll() }) else null
            visible.forEachIndexed { index, model ->
                val card = NotifCardView(context, model, tapOverride)
                card.tag = model.key
                cardsColumn.addView(card)
                if (animateIn) playCardEntrance(card, index)
            }
            scroll.scrollTo(0, 0)
            true
        } catch (t: Throwable) {
            Log.e("render failed", t)
            false
        }
    }

    /** Tap on the newest notification while collapsed: reveal everything. */
    private fun expandAll() {
        expanded = true
        render(lastModels, animateIn = true)
    }

    /** iOS-style card entrance: fade + slide up + slight spring-settle, staggered. */
    private fun playCardEntrance(card: View, index: Int) {
        try {
            card.alpha = 0f
            card.translationY = dp(24).toFloat()
            card.scaleX = 0.95f
            card.scaleY = 0.95f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((22L * index.coerceAtMost(8)))
                .setDuration(300L)
                .setInterpolator(springInterpolator)
                .withLayer()
                .start()
        } catch (t: Throwable) {
            // Animation is cosmetic - snap to final state instead.
            card.alpha = 1f
            card.translationY = 0f
            card.scaleX = 1f
            card.scaleY = 1f
        }
    }

    /** Cancels any in-flight hide so a fast re-open never snaps back to GONE. */
    fun cancelHideAnimation() {
        try {
            hideSafety?.let { mainHandler.removeCallbacks(it) }
            hideSafety = null
            animate().cancel()
        } catch (_: Throwable) {
        }
    }

    /**
     * Pre-warm the wallpaper drawable ONCE at attach time — never during a
     * gesture — so the first pull never blocks on a WallpaperManager query.
     */
    fun prewarmWallpaper() {
        try { refreshWallpaper() } catch (_: Throwable) {}
    }

    /**
     * Render cards off the gesture path (called when the notification set
     * changes while hidden). Creates views/icons once; later shows hit the
     * key-match fast path in render(). Keeps the view GONE.
     */
    fun prepareContent(models: List<NotifModel>) {
        try {
            if (visibility == VISIBLE) return
            render(models, animateIn = false)
        } catch (_: Throwable) {
        }
    }

    /**
     * Make the already-warmed, off-screen sheet VISIBLE on ACTION_DOWN so the
     * FIRST finger move paints immediately (no pop-in). Cheap: no DFS, no icon
     * loading — only a visibility flip and parking at expansion 0.
     */
    fun showInstantly() {
        try {
            if (visibility == VISIBLE) return
            animate().cancel()
            motion.cancelSettling()
            applyExpansion(0f, 0f, tracking = false)
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            visibility = VISIBLE
        } catch (_: Throwable) {
        }
    }

    /** Panel entrance: instant show; all motion comes from ShadeMotion. */
    fun animateShow() {
        try {
            // Cancel any in-flight hide (including its stale safety net) first,
            // otherwise a late GONE could yank the panel away mid-show.
            hideSafety?.let { mainHandler.removeCallbacks(it) }
            hideSafety = null
            animate().cancel()
            column.animate().cancel()
            motion.cancelSettling()
            pivotY = 0f
            refreshWallpaper()
            statusBar.updateTime()
            edgeView.animate().cancel()
            edgeView.alpha = 0f
            // The CLOCK must stay put (static, like the real lock screen).
            // Only the notification cards animate in (see render/playCardEntrance).
            cardsColumn.animate().cancel()
            cardsColumn.alpha = 1f
            cardsColumn.translationY = 0f
            statusBar.animate().cancel()
            statusBar.alpha = 1f
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = VISIBLE
        } catch (t: Throwable) {
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = VISIBLE
        }
    }

    /** Panel exit: fade out then GONE. Fallback guarantees onDone runs once. */
    fun animateHide(onDone: () -> Unit) {
        try {
            animate().cancel()
            var done = false
            val finish = Runnable {
                if (done) return@Runnable
                done = true
                hideSafety = null
                animate().cancel()
                visibility = GONE
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
                column.translationY = 0f
                column.alpha = 1f
                cardsColumn.animate().cancel()
                cardsColumn.alpha = 1f
                cardsColumn.translationY = 0f
                statusBar.animate().cancel()
                statusBar.alpha = 1f
                motion.snapTo(0f)
                expanded = false
                edgeView.animate().cancel()
                edgeView.alpha = 0f
                onDone()
            }
            hideSafety = finish
            // Unlock-style reveal (visual only — never touches the real keyguard):
            //  1) cards + status bar dissolve first (the notification layer leaves)
            //  2) the sheet glides up and fades, depth settling back to home
            try {
                cardsColumn.animate().cancel()
                cardsColumn.animate().alpha(0f).translationY(dp(-24).toFloat())
                    .setDuration(130L).setInterpolator(fastOutSlowIn).start()
                statusBar.animate().cancel()
                statusBar.animate().alpha(0f).setDuration(130L).start()
            } catch (_: Throwable) {
            }
            animate()
                .alpha(0f)
                .translationY(dp(-110).toFloat())
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(360L)
                .setInterpolator(fastOutSlowIn)
                .withLayer()
                .withEndAction { finish.run() }
                .start()
            // Safety net if the end action never fires (idempotent via `done`).
            mainHandler.postDelayed(finish, 480)
        } catch (t: Throwable) {
            hideSafety = null
            visibility = GONE
            alpha = 1f
            translationY = 0f
            edgeView.alpha = 0f
            onDone()
        }
    }

    /**
     * Applies window insets as padding: status bar + display cutout on top (punch-hole
     * safe), navigation bars on the sides/bottom. Never consumes the insets.
     */
    fun applyWindowInsets(insets: WindowInsets) {
        try {
            val mask = WindowInsets.Type.statusBars() or
                WindowInsets.Type.navigationBars() or
                WindowInsets.Type.displayCutout()
            val safe = insets.getInsets(mask)
            // Padding goes on the INNER content column, not the root: the sheet
            // must cover the whole screen so its rounded bottom clip is exact.
            column.setPadding(dp(16), safe.top + dp(4), dp(16), dp(16) + safe.bottom)
        } catch (t: Throwable) {
            Log.e("applyWindowInsets failed", t)
        }
    }

    /**
     * Feed the stock panel's expansion into [motion]. When our own gesture is
     * driving, motion ignores these (it is authoritative); otherwise it mirrors
     * Samsung so a pull started on the status bar still opens our panel.
     */
    /** Synchronous 1:1 tracking straight from the touch hook (no bus lag). */
    fun onFingerMove(offsetPx: Float) {
        try {
            val exp = (offsetPx / screenHeightPx).coerceIn(0f, 1f)
            // Keep motion in sync so the settle starts from the exact release value.
            motion.setExternalExpansion(exp)
            applyExpansion(exp, 0f, tracking = true)
        } catch (_: Throwable) {
        }
    }

    /** Called when the finger is released: settle from the exact position. */
    fun onFingerRelease(offsetPx: Float, velocity: Float) {
        try {
            val exp = (offsetPx / screenHeightPx).coerceIn(0f, 1f)
            motion.adoptAndSettle(exp, velocity)
        } catch (_: Throwable) {
        }
    }

    fun setPanelState(fraction: Float, onKeyguard: Boolean) {
        // While our finger drives the sheet synchronously, ignore Samsung's
        // async fraction (it would jump the sheet back). During settle/fling the
        // motion's animator owns the value, so ignore there too.
        if (motion.isAnimating) return
        if (com.iosshade.systemui.core.FingerTracker.active) return
        motion.onExternalFraction(fraction)
    }

    /** True while a settle/fling animation owns the sheet. */
    fun isMotionAnimating(): Boolean = motion.isAnimating

    /** Current absolute sheet offset in px (expansion * screen height). */
    fun currentOffsetPx(): Float = motion.expansion * screenHeightPx

    /**
     * The ONLY place that writes transforms. Every element derives from the one
     * authoritative [expansion], so finger tracking, fling settle and stock
     * mirroring all look identical - no competing animation systems.
     */
    private fun applyExpansion(expansion: Float, velocity: Float, tracking: Boolean) {
        try {
            val h = if (height > 0) height else screenHeightPx

            // Position follows EXACTLY the expansion passed in. During a finger
            // gesture the hook passes offsetPx/h; during settle motion passes its
            // animated value. No hidden state reads, no competing sources.
            sheet.translationY = -(h - (expansion * h).coerceIn(0f, h.toFloat()))

            // ---- Unlock-style exit animation --------------------------------
            // As the sheet is swiped up (expansion 1 -> 0), the notification
            // content dissolves and the clock zooms slightly, reproducing
            // Samsung's real lock-screen unlock feel while the wallpaper stays
            // sharp underneath. Driven purely by `expansion`, so it is exact
            // 1:1 with the finger and needs no keyguard / no password.
            //
            // closeProgress: 0 while open, ramps to 1 over the final 45% of the
            // upward swipe (expansion 0.45 -> 0).
            val closeProgress = ((0.45f - expansion) / 0.45f).coerceIn(0f, 1f)
            val remain = 1f - closeProgress

            // Cards: drift up and fade out as we close.
            column.translationY = -(1f - expansion) * dp(60) - closeProgress * dp(20)
            column.alpha = (0.55f + 0.45f * expansion) * remain

            // Clock: zooms in a touch and dissolves (real Samsung depth clock).
            clockLayer?.get()?.let { clock ->
                clock.pivotX = clock.width / 2f
                clock.pivotY = clock.height * 0.28f
                val scale = 0.96f + 0.04f * expansion + closeProgress * 0.08f
                clock.scaleX = scale
                clock.scaleY = scale
                clock.alpha = remain
            }

            // Wallpaper: subtle zoom-out back to rest (depth effect returning).
            wallpaper.scaleX = 1f + closeProgress * 0.06f
            wallpaper.scaleY = 1f + closeProgress * 0.06f
            // Status bar fades with the rest of the shade chrome.
            statusBar.alpha = remain

            // White end-line rides the rounded edge. Set alpha directly (no
            // animator allocation) during the gesture; only run a fade animator
            // on the one transition to hidden.
            val moving = (tracking || motion.isAnimating) &&
                expansion >= 0.03f && expansion <= 0.98f
            if (moving) {
                if (edgeView.alpha < 1f) {
                    edgeAnimating.set(false)
                    edgeView.animate().cancel()
                    edgeView.alpha = 1f
                }
            } else if (edgeView.alpha > 0f && edgeAnimating.compareAndSet(false, true)) {
                fadeEdgeOut()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Keeps Samsung's clock untouched (its own depth effect must work).
     * No recoloring / no periodic tick: Samsung draws the clock THROUGH the
     * wallpaper subject, and repainting it breaks that effect.
     */
    private fun paintClockWhite(view: View?) {
        // Intentionally empty - kept as the single clock-setup hook point.
    }

    /** Inserts the reparented clock layer INSIDE the clipped sheet, above wallpaper. */
    fun addClockLayer(layer: View) {
        try {
            sheet.addView(
                layer,
                1.coerceAtMost(sheet.childCount),
                layer.layoutParams ?: LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                )
            )
        } catch (t: Throwable) {
            Log.e("addClockLayer failed", t)
        }
    }

    /** Called by the controller when it reparents Samsung's clock layer in/out. */
    fun setClockLayer(view: View?) {
        clockLayer = view?.let { java.lang.ref.WeakReference(it) }
        paintClockWhite(view)
        // Apply the current transform immediately so it never pops in at scale 1.
        applyExpansion(motion.expansion, motion.velocity, motion.isTracking)
    }

    /** Smoothly fades the shade edge out (used when the panel settles). */
    private fun fadeEdgeOut() {
        try {
            edgeView.animate().cancel()
            edgeView.animate().alpha(0f).setDuration(100L)
                .withEndAction { edgeAnimating.set(false) }
                .start()
        } catch (_: Throwable) {
            edgeAnimating.set(false)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    // Cached density — avoids a displayMetrics lookup on every dp() call (hot path).
    private val density: Float = resources.displayMetrics.density
    private val screenHeightPx: Int = resources.displayMetrics.heightPixels
    private fun dp(value: Int): Int =
        (value * density + 0.5f).toInt()

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun iconColor(): Int =
        if (isNight()) 0xFFFFFFFF.toInt() else 0xFF202124.toInt()

    private fun ViewGroupLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    /** Tiny canvas-drawn glyphs (no Android resources touched - nothing to clash). */
    private class GlyphView(
        context: android.content.Context,
        private val kind: Int,
        private val tint: Int
    ) : View(context) {

        companion object {
            const val KIND_FLASH = 0
            const val KIND_CAMERA = 1
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            try {
                if (width == 0 || height == 0) return
                fill.color = tint
                stroke.color = tint
                val s = width / 24f
                stroke.strokeWidth = 1.8f * s

                if (kind == KIND_FLASH) {
                    // A pocket torch: wide head, gap, narrower body.
                    canvas.drawRoundRect(7.5f * s, 3.5f * s, 16.5f * s, 7.5f * s, 1.6f * s, 1.6f * s, fill)
                    canvas.drawRoundRect(10.2f * s, 9f * s, 13.8f * s, 19.5f * s, 1.6f * s, 1.6f * s, fill)
                } else {
                    // Camera body + lens + top bump.
                    canvas.drawRoundRect(3.5f * s, 7f * s, 20.5f * s, 19f * s, 3f * s, 3f * s, stroke)
                    canvas.drawCircle(12f * s, 13f * s, 3.6f * s, stroke)
                    canvas.drawRoundRect(9f * s, 4.6f * s, 15f * s, 7.5f * s, 1.2f * s, 1.2f * s, fill)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
