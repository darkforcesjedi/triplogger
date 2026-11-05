package com.mallner.triplogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Android/Canvas port of imagegen.CreatePlot from the TorqueLogs converter:
 * 200 px per minute of log time (capped at 38400 px wide), 720 px tall,
 * white background, gray grid in 10 mph / 60 s steps, red dots = OBD speed,
 * blue dots = GPS speed, FreeSans 24 px labels.
 */
class AndroidPlotRenderer(private val context: Context) : PlotRenderer {

    companion object {
        private const val TAG = "AndroidPlotRenderer"
        private const val PX_PER_MINUTE = 200
        private const val MAX_WIDTH = 38400
        private const val HEIGHT = 720
        private const val FONT_PX = 24f
    }

    private val font: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "fonts/FreeSans.ttf")
        } catch (e: Exception) {
            Log.w(TAG, "FreeSans.ttf unavailable, using default font", e)
            Typeface.DEFAULT
        }
    }

    override fun renderPng(label: String, obdSpeedsMph: List<Pair<Long, Float>>, gpsSpeedsMph: List<Pair<Long, Float>>): ByteArray {
        var minx = Double.MAX_VALUE
        var maxx = -Double.MAX_VALUE
        if (obdSpeedsMph.isNotEmpty()) {
            minx = obdSpeedsMph.first().first / 1000.0
            maxx = obdSpeedsMph.last().first / 1000.0
        }
        if (gpsSpeedsMph.isNotEmpty()) {
            minx = min(minx, gpsSpeedsMph.first().first / 1000.0)
            maxx = max(maxx, gpsSpeedsMph.last().first / 1000.0)
        }
        if (minx > maxx) return ByteArray(0)

        var maxy = -Double.MAX_VALUE
        var found = false
        for ((_, s) in obdSpeedsMph) if (!s.isNaN()) { maxy = max(maxy, s.toDouble()); found = true }
        for ((_, s) in gpsSpeedsMph) if (!s.isNaN()) { maxy = max(maxy, s.toDouble()); found = true }
        if (!found) return ByteArray(0)

        val miny = 0.0
        maxy = ceil(maxy)
        if (maxy <= miny || maxx <= minx) return ByteArray(0)

        var w = PX_PER_MINUTE * ceil((maxx - minx) / 60.0).toInt()
        if (w > MAX_WIDTH) w = MAX_WIDTH
        if (w <= 0) return ByteArray(0)
        val h = HEIGHT

        val scaleX = w / (maxx - minx)
        val scaleY = h / (maxy - miny)
        val gridX = 60 * scaleX
        val gridY = 10 * scaleY

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(128, 128, 128)
                strokeWidth = 1f
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(128, 128, 128)
                typeface = font
                textSize = FONT_PX
            }
            val whitePaint = Paint().apply { color = Color.WHITE }

            // Border (outline only - a filled rect would gray out the whole plot)
            val borderPaint = Paint(gridPaint).apply { style = Paint.Style.STROKE }
            canvas.drawRect(0f, 0f, (w - 1).toFloat(), (h - 1).toFloat(), borderPaint)

            val bounds = Rect()

            // Horizontal grid lines + mph labels
            var ypos = h - gridY
            var mph = -10.0
            while (ypos > 0) {
                mph = (h - ypos) / scaleY + miny
                canvas.drawLine(0f, ypos.toFloat(), w.toFloat(), ypos.toFloat(), gridPaint)
                drawTopLeftText(canvas, String.format("%.0f", mph), 1f, (ypos + 1).toFloat(), textPaint)
                ypos -= gridY
            }
            if (maxy - mph > 2) {
                drawTopLeftText(canvas, String.format("%.0f", maxy), 1f, 1f, textPaint)
            }

            // Vertical grid lines (every minute) + time labels
            var xpos = (ceil(minx / 60.0) * 60 - minx) * scaleX
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            while (xpos < w) {
                if (xpos > 30 && xpos < w - 30) {
                    canvas.drawLine(xpos.toFloat(), 0f, xpos.toFloat(), h.toFloat(), gridPaint)
                    val stime = timeFmt.format((xpos / scaleX + minx) * 1000.0)
                    textPaint.getTextBounds(stime, 0, stime.length, bounds)
                    canvas.drawRect(
                        (xpos + 1).toFloat(), 1f,
                        (xpos + 1 + bounds.width()).toFloat(),
                        (1 + bounds.height()).toFloat(),
                        whitePaint
                    )
                    drawTopLeftText(canvas, stime, (xpos + 1).toFloat(), 1f, textPaint)
                }
                xpos += gridX
            }

            // Data points: red = OBD (dataset 1), blue = GPS (dataset 2)
            val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.FILL
            }
            val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLUE
                style = Paint.Style.FILL
            }
            for ((ts, spd) in obdSpeedsMph) {
                if (spd.isNaN()) continue
                val x = ((ts / 1000.0 - minx) * scaleX).toFloat()
                val y = (h - (spd - miny) * scaleY).toFloat()
                canvas.drawCircle(x, y, 3f, redPaint)
            }
            for ((ts, spd) in gpsSpeedsMph) {
                if (spd.isNaN()) continue
                val x = ((ts / 1000.0 - minx) * scaleX).toFloat()
                val y = (h - (spd - miny) * scaleY).toFloat()
                canvas.drawCircle(x, y, 3f, bluePaint)
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** PIL draws text with a top-left anchor; Canvas uses the baseline, so shift by font metrics. */
    private fun drawTopLeftText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val fm = paint.fontMetrics
        canvas.drawText(text, x, y - fm.top, paint)
    }
}
