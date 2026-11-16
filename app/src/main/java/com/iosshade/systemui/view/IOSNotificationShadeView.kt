package com.iosshade.systemui.view

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import com.iosshade.systemui.core.Fonts
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.data.NotifModel
import com.iosshade.systemui.hooks.PanelExpansionHook

/**
 * iOS lock-screen "Notification Center" look (Phase 2.1), matched to the user's
 * reference video:
 *
 *   FrameLayout (translucent dark glass - wallpaper stays visible behind)
 *   ├─ ScrollView
 *   │  └─ LinearLayout (column)
 *   │     ├─ clock block:  date (small) + time (huge thin, centered)
 *   │     ├─ header row:   "Notification Center" (left) ... [x] (right)
 *   │     └─ NotifCardView * N   (staggered entrance animation)
 *   └─ bottom row: [flashlight] ..... [camera]  (round glass lock-screen buttons)
 *
 * The clock is a system TextClock (self-updating, zero timers of our own).
 * Tapping the empty scroll area closes the panel, like the video's x button.
 * Every animation degrades to an instant visibility flip if the animator fails.
 */
class IOSNotificationShadeView(context: android.content.Context) : FrameLayout(context) {

    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val cardsColumn = LinearLayout(context)
    private val dateView = TextClock(context)
    private val timeView = TextClock(context)
    private val titleView = TextView(context)
    private val closeButton = TextView(context)
    private var torchButton: FrameLayout? = null
    private var torchOn = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending hide safety-net (cancelled whenever the panel is shown again). */
    private var hideSafety: Runnable? = null

    private val showInterpolator = DecelerateInterpolator(2.2f)
    private val springInterpolator = OvershootInterpolator(0.8f)
    private val hideInterpolator = AccelerateInterpolator(1.2f)

    /** True while the user is swipe-closing the panel with a finger down. */
    private var userDragging = false
    private var dragStartY = 0f

    init {
        clipToPadding = false
        clipChildren = false

        // Clear backdrop: the wallpaper shows through fully (the user asked for
        // no frost/blur). A whisper of dim is added only while rolling (see
        // setPanelState) and fades to perfectly clear at full expansion.
        setBackgroundColor(0x00000000)

        scroll.isFillViewport = true
        scroll.overScrollMode = ScrollView.OVER_SCROLL_NEVER
        scroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scroll.addView(column, ViewGroupLayoutParams())
        addView(scroll)

        // Tap on empty area = close (children like cards and the x keep their own taps).
        scroll.isClickable = true
        scroll.setOnClickListener { closePanel() }

        // Swipe-up-to-close, iOS lock-screen style: the content tracks the finger
        // 1:1 while pushing up; release far enough and the panel closes, release
        // early and it springs back. Purely observational (never consumes the
        // touch), so card taps and list scrolling keep working.
        scroll.setOnTouchListener { _, ev ->
            try {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dragStartY = ev.rawY
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - dragStartY
                        // Swipe up only, and only when the list itself cannot scroll
                        // further (so scrolling long lists never fights the close).
                        if (dy < 0 && !scroll.canScrollVertically(1)) {
                            userDragging = true
                            column.translationY = dy * 0.85f
                            column.alpha = (1f + dy / dp(300)).coerceIn(0.25f, 1f)
                        } else if (dy > 0) {
                            userDragging = false
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (userDragging) {
                            userDragging = false
                            val dy = ev.rawY - dragStartY
                            if (dy < -dp(80)) {
                                closePanel()
                            } else {
                                column.animate().cancel()
                                column.animate()
                                    .translationY(0f)
                                    .alpha(1f)
                                    .setDuration(280L)
                                    .setInterpolator(springInterpolator)
                                    .withLayer()
                                    .start()
                            }
                        }
                        false
                    }
                    else -> false
                }
            } catch (_: Throwable) {
                false
            }
        }

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(0, 0, 0, dp(24))

        buildClock()
        buildHeader()

        // Dedicated container for notification cards: render() only ever touches
        // this child, so clock/header indices can never be disturbed.
        cardsColumn.orientation = LinearLayout.VERTICAL
        // Extra bottom padding so the last card never hides under the lock buttons.
        cardsColumn.setPadding(0, 0, 0, dp(84))
        column.addView(
            cardsColumn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        buildLockButtons()

        // Default: inert. The controller toggles clickable only while visible.
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    // ------------------------------------------------------------------
    // Clock block (iOS lock screen style)
    // ------------------------------------------------------------------
    private fun buildClock() {
        try {
            val clockBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            dateView.apply {
                // "Tue Oct 7" like the reference video (locale-aware).
                format12Hour = "EEE MMM d"
                format24Hour = "EEE MMM d"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(if (isNight()) 0xE6FFFFFF.toInt() else 0xCC000000.toInt())
                typeface = Fonts.regular(context)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            timeView.apply {
                format12Hour = "h:mm"
                format24Hour = "HH:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 88f)
                setTextColor(if (isNight()) 0xF2FFFFFF.toInt() else 0xF0000000.toInt())
                typeface = Fonts.light(context)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

            clockBlock.addView(
                dateView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            clockBlock.addView(
                timeView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            )

            column.addView(
                clockBlock,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(14); bottomMargin = dp(20) }
            )
        } catch (t: Throwable) {
            Log.e("buildClock failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Header: left-aligned title + right x (like the video)
    // ------------------------------------------------------------------
    private fun buildHeader() {
        try {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            titleView.text = "Notification Center"
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            titleView.typeface = Fonts.semiBold(context)
            titleView.setTextColor(if (isNight()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            titleView.maxLines = 1

            closeButton.text = "×"
            closeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            closeButton.setTextColor(if (isNight()) 0xD9FFFFFF.toInt() else 0xB3000000.toInt())
            closeButton.typeface = Fonts.light(context)
            closeButton.setPadding(dp(14), dp(4), dp(14), dp(10))
            closeButton.setOnClickListener { closePanel() }

            row.addView(
                titleView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(closeButton)

            column.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(16) }
            )
        } catch (t: Throwable) {
            Log.e("buildHeader failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Bottom lock-screen buttons: flashlight + camera
    // ------------------------------------------------------------------
    private fun buildLockButtons() {
        try {
            val row = FrameLayout(context)

            val torch = circleButton(GlyphView(context, GlyphView.KIND_FLASH, iconColor()))
            torch.setOnClickListener { toggleTorch() }
            torchButton = torch

            val camera = circleButton(GlyphView(context, GlyphView.KIND_CAMERA, iconColor()))
            camera.setOnClickListener { openCamera() }

            val size = LayoutParams(dp(52), dp(52))
            row.addView(
                torch,
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.START)
                    .apply { leftMargin = dp(44); bottomMargin = dp(8) }
            )
            row.addView(
                camera,
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
                    .apply { rightMargin = dp(44); bottomMargin = dp(8) }
            )

            addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            )
        } catch (t: Throwable) {
            Log.e("buildLockButtons failed", t)
        }
    }

    private fun circleButton(glyph: View): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x47FFFFFF)
            }
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
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
     * Rebuilds the card list. Returns true when there is content to display
     * (the controller uses this to fall back to the stock shade when empty).
     *
     * @param animateIn when true, cards play a staggered slide-up + fade entrance
     *                  (used when the panel itself is appearing).
     */
    fun render(models: List<NotifModel>, animateIn: Boolean): Boolean {
        return try {
            cardsColumn.removeAllViews()
            if (models.isEmpty()) return false

            models.forEachIndexed { index, model ->
                val card = NotifCardView(context, model)
                cardsColumn.addView(card)
                if (animateIn) playCardEntrance(card, index)
            }
            // Return to top for the fresh set.
            scroll.scrollTo(0, 0)
            true
        } catch (t: Throwable) {
            Log.e("render failed", t)
            false
        }
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
                .setStartDelay((50L * index.coerceAtMost(8)))
                .setDuration(360L)
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

    /** Panel entrance: slide down from behind the status bar + fade + subtle scale. */
    fun animateShow() {
        try {
            // Cancel any in-flight hide (including its stale safety net) first,
            // otherwise a late GONE could yank the panel away mid-show.
            hideSafety?.let { mainHandler.removeCallbacks(it) }
            hideSafety = null
            animate().cancel()
            column.animate().cancel()
            userDragging = false
            pivotY = 0f
            alpha = 0f
            translationY = dp(-48).toFloat()
            scaleX = 0.985f
            scaleY = 0.985f
            visibility = VISIBLE
            animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400L)
                .setInterpolator(showInterpolator)
                .withLayer()
                .start()
        } catch (t: Throwable) {
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = VISIBLE
        }
    }

    /** Panel exit: slide up + fade, then GONE. Falls back to instant hide. */
    fun animateHide(onDone: () -> Unit) {
        try {
            animate().cancel()
            val safety = Runnable {
                hideSafety = null
                if (visibility != GONE) {
                    visibility = GONE
                    alpha = 1f
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    // Reset the finger-follow transform so the next show starts clean.
                    column.translationY = 0f
                    column.alpha = 1f
                    userDragging = false
                    onDone()
                }
            }
            hideSafety = safety
            animate()
                .alpha(0f)
                .translationY(dp(-40).toFloat())
                .setDuration(230L)
                .setInterpolator(hideInterpolator)
                .withLayer()
                .withEndAction { safety.run() }
                .start()
            // Safety net: if the end action never fires (rare animator edge cases),
            // force the hidden state shortly after. Idempotent via visibility check.
            mainHandler.postDelayed(safety, 450)
        } catch (t: Throwable) {
            hideSafety = null
            visibility = GONE
            alpha = 1f
            translationY = 0f
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
            setPadding(dp(14), safe.top + dp(4), dp(14), safe.bottom + dp(4))
        } catch (t: Throwable) {
            Log.e("applyWindowInsets failed", t)
        }
    }

    /**
     * The signature iOS move: while the panel is pulled down the content
     * physically FOLLOWS THE FINGER (slides in from behind the status bar,
     * fading up), like the lock-screen swipe. The backdrop itself stays clear
     * - the wallpaper is always visible, no frost/blur.
     */
    fun setPanelState(fraction: Float, onKeyguard: Boolean) {
        try {
            val f = fraction.coerceIn(0f, 1f)
            val t = ((f - 0.04f) / 0.96f).coerceIn(0f, 1f)
            if (!userDragging) {
                column.translationY = -(1f - t) * dp(150)
                column.alpha = 0.3f + 0.7f * t
            }
            // Whisper of dim while rolling, perfectly clear when fully down.
            val alpha = (0x30 + ((0x00 - 0x30) * t).toInt()).coerceIn(0, 255)
            setBackgroundColor(alpha shl 24)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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
