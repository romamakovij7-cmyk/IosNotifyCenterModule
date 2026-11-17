package com.iosshade.systemui.view

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.iosshade.systemui.core.Fonts
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.data.NotifModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single iOS lock-screen notification card (Phase 2.1), matched to the video:
 *
 *  [icon 36dp]  Title (bold) .................. 4:29
 *               Body  (up to 3 lines)
 *
 * No app-name row and no chevron - exactly like the reference video. The card
 * background is light frosted glass (white at low alpha over the wallpaper).
 * Construction is fully wrapped in try/catch: a malformed notification can
 * never crash SystemUI.
 */
class NotifCardView(
    context: android.content.Context,
    private val model: NotifModel,
    /** When set, a tap runs this instead of opening the app (collapse/expand flow). */
    private val onTapOverride: (() -> Unit)? = null
) : LinearLayout(context) {

    init {
        try {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))

            // iOS-style card: solid translucent fill with a hairline rim for
            // crisp definition over any wallpaper.
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (isNight()) 0xE62C2C2E.toInt() else 0xEBFFFFFF.toInt())
                setStroke(dp(1), if (isNight()) 0x1AFFFFFF else 0x14000000)
            }
            elevation = dp(4).toFloat()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }

            addView(buildIcon())
            addView(buildTextColumn())
            setOnClickListener { onTapOverride?.invoke() ?: open() }

            // iOS-style swipe-up to dismiss: the card follows the finger and,
            // once released past a threshold, flies away and is dismissed.
            var downY = 0f
            var dismissing = false
            setOnTouchListener { v, ev ->
                try {
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downY = ev.rawY
                            false
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dy = ev.rawY - downY
                            if (dy < 0) {
                                v.translationY = dy * 0.7f
                                v.alpha = (1f + dy / dp(240)).coerceIn(0.35f, 1f)
                            }
                            false
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            val dy = ev.rawY - downY
                            if (dy < -dp(70) && ev.actionMasked != android.view.MotionEvent.ACTION_CANCEL) {
                                dismissing = true
                                v.animate()
                                    .translationY(-dp(300).toFloat())
                                    .alpha(0f)
                                    .setDuration(160L)
                                    .withEndAction {
                                        try {
                                            com.iosshade.systemui.hooks.NotificationSourceHook
                                                .dismiss(model.key)
                                        } catch (_: Throwable) {
                                        }
                                    }
                                    .start()
                                true
                            } else {
                                v.animate().translationY(0f).alpha(1f).setDuration(140L).start()
                                false
                            }
                        }
                        else -> false
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (t: Throwable) {
            Log.e("NotifCardView init failed for ${model.packageName}", t)
        }
    }

    // ------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------
    private fun buildIcon(): ImageView {
        val iconView = ImageView(context).apply {
            layoutParams = LayoutParams(dp(40), dp(40))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        try {
            model.icon?.loadDrawable(context)?.let { iconView.setImageDrawable(it) }
        } catch (t: Throwable) {
            // Icon packs can be unreadable cross-process; a missing icon is cosmetic only.
            Log.w("icon load failed for ${model.packageName}: ${t.message}")
        }
        return iconView
    }

    private fun buildTextColumn(): LinearLayout {
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(13) }
        }

        // Row 1: title (bold, left) + timestamp (right) - like the video.
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = model.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.semiBold(context)
            setTextColor(primaryTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val timeView = TextView(context).apply {
            text = formatTime(model.timeMillis)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Fonts.regular(context)
            setTextColor(secondaryTextColor())
            gravity = Gravity.END
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }
        topRow.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(timeView)
        textColumn.addView(topRow)

        // Row 2: body (optional)
        if (model.text.isNotBlank()) {
            val bodyView = TextView(context).apply {
                text = model.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Fonts.regular(context)
                setTextColor(primaryTextColor())
                alpha = 0.85f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            textColumn.addView(bodyView)
        }

        return textColumn
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------
    private fun open() {
        try {
            val pendingIntent = model.contentIntent
            if (pendingIntent != null) {
                pendingIntent.send()
                return
            }
            val launch = context.packageManager.getLaunchIntentForPackage(model.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        } catch (t: Throwable) {
            Log.e("open() failed for ${model.packageName}", t)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun cardColor(): Int =
        if (isNight()) 0x59FFFFFF.toInt() else 0xE6FFFFFF.toInt()

    private fun strokeColor(): Int =
        if (isNight()) 0x14FFFFFF else 0x1A000000

    private fun formatTime(millis: Long): String = try {
        val pattern = if (android.text.format.DateUtils.isToday(millis)) "HH:mm" else "dd.MM HH:mm"
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    } catch (t: Throwable) {
        ""
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun primaryTextColor(): Int =
        if (isNight()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    private fun secondaryTextColor(): Int =
        if (isNight()) 0xB3FFFFFF.toInt() else 0x8A000000.toInt()
}
