package com.iosshade.systemui.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.TelephonyManager
import android.view.View
import com.iosshade.systemui.core.Fonts
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * iOS-style status bar drawn by us (the stock one is hidden when the shade is open).
 *
 *   left:  14:17   KYIVSTAR
 *   right: ▂▄▆█  ◡   [34│]
 */
class StatusBarView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density

    /**
     * Height of the system status-bar strip, derived from real WindowInsets
     * (max of statusBars + displayCutout top). Edge-to-edge windows report a
     * statusBars inset of 0 but still carry a displayCutout top, so we take the
     * max. This guarantees the bar occupies the real top strip instead of a
     * hardcoded dp(30) that gets clipped by the cutout / punched-out camera.
     */
    private var topInsetPx: Int = 0

    fun setTopInset(px: Int) {
        if (px == topInsetPx) return
        topInsetPx = px
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always size the bar to the actual status-bar strip height. A plain
        // View with no onMeasure can measure to 0 during early edge-to-edge
        // layout passes, collapsing cy and pushing icons above the screen top.
        val h = if (topInsetPx > 0) {
            topInsetPx
        } else {
            // Fallback: system status_bar_height dimen, never below 24dp.
            val fallback = try {
                val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) resources.getDimensionPixelSize(id) else 0
            } catch (_: Throwable) {
                0
            }
            maxOf(fallback, (24 * d).toInt())
        }
        setMeasuredDimension(
            android.view.View.resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            h
        )
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Fonts.semiBold(context)
        textAlign = Paint.Align.LEFT
    }
    private val carrierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        typeface = Fonts.regular(context)
        textAlign = Paint.Align.LEFT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
    }

    private var batteryPct = 0
    private var charging = false
    private var wifiLevel = 4
    private var carrier: String = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
                charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                    BatteryManager.BATTERY_STATUS_CHARGING
                invalidate()
            }
        }
    }

    init {
        val pad = (18 * d).toInt()
        setPadding(pad, (9 * d).toInt(), pad, 0)

        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
                charging = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                    BatteryManager.BATTERY_STATUS_CHARGING
            }
        } catch (_: Throwable) {
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.let { wifiLevel = WifiManager.calculateSignalLevel(it.connectionInfo.rssi, 5).coerceIn(0, 4) }
        } catch (_: Throwable) {
        }
        try {
            val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            carrier = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: ""
        } catch (_: Throwable) {
        }
        if (carrier.isBlank()) carrier = "KYIVSTAR"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
        }
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        super.onDetachedFromWindow()
    }

    fun updateTime() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = paddingTop + (height - paddingTop) / 2f

        // ---- LEFT: time (bold white) + carrier name (soft) ----
        val time = try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
        } catch (_: Throwable) {
            "00:00"
        }
        textPaint.textSize = 15f * d
        canvas.drawText(time, paddingLeft.toFloat(), cy + 5.5f * d, textPaint)

        val timeW = textPaint.measureText(time)
        carrierPaint.textSize = 12f * d
        canvas.drawText(carrier, paddingLeft + timeW + 9f * d, cy + 4.5f * d, carrierPaint)

        // ---- RIGHT: cellular bars, wifi, battery pill with % ----
        var rx = (width - paddingRight).toFloat()
        drawBattery(canvas, rx, cy)
        rx -= 38f * d
        drawWifi(canvas, rx, cy)
        rx -= 22f * d
        drawCellular(canvas, rx, cy)
    }

    /** Battery: rounded pill with a % inside + a small terminal nub. */
    private fun drawBattery(canvas: Canvas, right: Float, cy: Float) {
        val bw = 27f * d
        val bh = 14f * d
        val r = 4f * d
        val left = right - bw
        val top = cy - bh / 2
        val rect = RectF(left, top, right, cy + bh / 2)

        fill.color = if (charging) 0xFF34C759.toInt() else 0x2EFFFFFF
        canvas.drawRoundRect(rect, r, r, fill)

        // terminal nub
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(
            RectF(right + 1.5f * d, cy - 4f * d, right + 3.5f * d, cy + 4f * d),
            1f * d, 1f * d, fill
        )

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 10.5f * d
        textPaint.color = 0xFF000000.toInt()
        canvas.drawText("$batteryPct", left + bw / 2, cy + 3.8f * d, textPaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** Wifi: three clean arcs + a base dot. */
    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float) {
        stroke.strokeWidth = 2.1f * d
        stroke.color = 0xFFFFFFFF.toInt()
        val radii = floatArrayOf(3.2f, 5.6f, 8f)
        for (i in 0 until 3) {
            val rr = radii[i] * d
            canvas.drawArc(
                RectF(cx - rr, cy - rr + 1f * d, cx + rr, cy + rr + 1f * d),
                205f, 130f, false, stroke
            )
        }
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy + 3.4f * d, 1.9f * d, fill)
    }

    /** Cellular: four bars of increasing height, filled (iOS-like). */
    private fun drawCellular(canvas: Canvas, right: Float, cy: Float) {
        val bw = 2.6f * d
        val gap = 2f * d
        val heights = floatArrayOf(4f, 6.5f, 9f, 11.5f)
        fill.color = 0xFFFFFFFF.toInt()
        for (i in heights.indices) {
            val x = right - (3 - i) * (bw + gap) - bw
            val bh = heights[i] * d
            canvas.drawRoundRect(
                RectF(x, cy - bh / 2, x + bw, cy + bh / 2),
                0.8f * d, 0.8f * d, fill
            )
        }
    }
}
