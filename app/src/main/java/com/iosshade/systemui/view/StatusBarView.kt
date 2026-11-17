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
import android.view.View
import android.widget.TextView
import com.iosshade.systemui.core.Fonts
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * An iOS-style status bar drawn by us (the stock one is hidden by One UI when
 * the shade is open). Layout mirrors the reference screenshot:
 *
 *   left:  14:12   ➤ 🔒 ✕ ✕
 *   right: ▂▄▆  ◡   [33%]
 */
class StatusBarView(context: Context) : View(context) {

    private val timeView = TextView(context).apply {
        textSize = 15f
        typeface = Fonts.semiBold(context)
        setTextColor(0xFFFFFFFF.toInt())
        includeFontPadding = false
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15f * resources.displayMetrics.density
        typeface = Fonts.semiBold(context)
        textAlign = Paint.Align.RIGHT
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
    }

    private var batteryPct = 0
    private var batteryCharging = false
    private var wifiLevel = 4 // 0..4
    private var cellularLevel = 4 // 0..4

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
                    batteryCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                        BatteryManager.BATTERY_STATUS_CHARGING
                    invalidate()
                }
            }
        }
    }

    init {
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, (10 * resources.displayMetrics.density).toInt(), pad, 0)
        // Initial battery state (sticky broadcast).
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
            }
        } catch (_: Throwable) {
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.let {
                val rssi = it.connectionInfo.rssi
                wifiLevel = WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
            }
        } catch (_: Throwable) {
        }
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

    fun updateTime() {
        try {
            timeView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            invalidate()
        } catch (_: Throwable) {
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val cy = paddingTop + (h - paddingTop) / 2f
        val d = resources.displayMetrics.density

        // --- LEFT: time + small status glyphs ---
        val timeText = try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
        } catch (_: Throwable) {
            "00:00"
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 15f * d
        canvas.drawText(timeText, paddingLeft.toFloat(), cy + 5f * d, textPaint)
        var x = paddingLeft + timeView.paint.measureText(timeText) + 10f * d

        // location arrow
        drawArrow(canvas, x + 6f * d, cy, 6f * d)
        x += 18f * d
        // cross / precise-location / rotation glyphs (small filled shapes)
        drawStar(canvas, x + 5f * d, cy, 5f * d)
        x += 16f * d
        drawX(canvas, x + 5f * d, cy, 5f * d)
        x += 16f * d
        drawX(canvas, x + 5f * d, cy, 5f * d)

        // --- RIGHT: cellular, wifi, battery with % ---
        val right = (width - paddingRight).toFloat()
        var rx = right
        // Battery pill with percentage inside (green when charging).
        val bw = 30f * d
        val bh = 15f * d
        val battRect = RectF(rx - bw, cy - bh / 2, rx, cy + bh / 2)
        iconPaint.color = if (batteryCharging) 0xFF34C759.toInt() else 0x33FFFFFF
        canvas.drawRoundRect(battRect, 4f * d, 4f * d, iconPaint)
        val pctText = "$batteryPct"
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f * d
        textPaint.color = 0xFF000000.toInt()
        canvas.drawText(pctText, rx - bw / 2, cy + 4f * d, textPaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        rx -= bw + 8f * d

        // Wifi arc
        drawWifi(canvas, rx - 8f * d, cy, 8f * d)
        rx -= 20f * d

        // Cellular bars (4 bars + triangle)
        drawCellular(canvas, rx - 9f * d, cy, 9f * d)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val p = android.graphics.Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s, cy + s * 0.6f)
            lineTo(cx, cy + s * 0.2f)
            lineTo(cx - s, cy + s * 0.6f)
            close()
        }
        canvas.drawPath(p, iconPaint)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1.5f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, s, strokePaint)
        canvas.drawLine(cx - s, cy, cx + s, cy, strokePaint)
        canvas.drawLine(cx, cy - s, cx, cy + s, strokePaint)
        iconPaint.style = Paint.Style.FILL
    }

    private fun drawX(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        strokePaint.strokeWidth = 1.6f * resources.displayMetrics.density
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, strokePaint)
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, strokePaint)
    }

    private fun drawWifi(canvas: Canvas, right: Float, cy: Float, s: Float) {
        val paint = strokePaint
        paint.strokeWidth = 2f * resources.displayMetrics.density
        for (i in 0 until 3) {
            val r = s * (0.3f + i * 0.3f)
            canvas.drawArc(
                RectF(right - r, cy - r, right + r, cy + r),
                200f, 140f, false, paint
            )
        }
        iconPaint.style = Paint.Style.FILL
        canvas.drawCircle(right, cy + s * 0.6f, 2f * resources.displayMetrics.density, iconPaint)
    }

    private fun drawCellular(canvas: Canvas, right: Float, cy: Float, s: Float) {
        val bars = 4
        for (i in 0 until bars) {
            val bh = s * (0.35f + i * 0.22f)
            val bw = 2.5f * resources.displayMetrics.density
            val x = right - (bars - 1 - i) * (bw + 2f * resources.displayMetrics.density)
            canvas.drawRect(x, cy - bh / 2, x + bw, cy + bh / 2, iconPaint)
        }
    }
}
