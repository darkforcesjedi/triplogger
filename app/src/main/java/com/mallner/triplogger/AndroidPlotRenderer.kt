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
 * white background, gray grid in 10 mph (or 20 km/h) / 60 s steps, red dots = OBD speed,
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

    override fun renderPng(label: String, speedUnit: String, tzOffsetMin: Int?, tzChanges: List<Pair<Long, Int>>, dateChanges: List<Pair<Long, String>>, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray {
        var minx = Double.MAX_VALUE
        var maxx = -Double.MAX_VALUE
        if (obdSpeeds.isNotEmpty()) {
            minx = obdSpeeds.first().first / 1000.0
            maxx = obdSpeeds.last().first / 1000.0
        }
        if (gpsSpeeds.isNotEmpty()) {
            minx = min(minx, gpsSpeeds.first().first / 1000.0)
            maxx = max(maxx, gpsSpeeds.last().first / 1000.0)
        }
        if (minx > maxx) return ByteArray(0)

        var maxy = -Double.MAX_VALUE
        var found = false
        for ((_, s) in obdSpeeds) if (!s.isNaN()) { maxy = max(maxy, s.toDouble()); found = true }
        for ((_, s) in gpsSpeeds) if (!s.isNaN()) { maxy = max(maxy, s.toDouble()); found = true }
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
        val speedStep = if (speedUnit == "km/h") 20.0 else 10.0  // keep gridline density similar
        val gridY = speedStep * scaleY

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

            // Horizontal grid lines + speed labels
            var ypos = h - gridY
            var speed = -speedStep
            while (ypos > 0) {
                speed = (h - ypos) / scaleY + miny
                canvas.drawLine(0f, ypos.toFloat(), w.toFloat(), ypos.toFloat(), gridPaint)
                drawTopLeftText(canvas, String.format("%.0f", speed), 1f, (ypos + 1).toFloat(), textPaint)
                ypos -= gridY
            }
            if (maxy - speed > 2) {
                drawTopLeftText(canvas, String.format("%.0f", maxy), 1f, 1f, textPaint)
            }

            // Vertical grid lines (every minute) + time labels. Labels show the wall
            // clock actually in effect at each instant, so they jump forward/back
            // when the UTC offset changes; the change itself gets a dashed gridline.
            var xpos = (ceil(minx / 60.0) * 60 - minx) * scaleX
            val offsetChanges = tzChanges.sortedBy { it.first }
            fun offsetAt(ms: Long): Int {
                var off: Int? = null
                for ((changeMs, newOff) in offsetChanges) {
                    if (ms >= changeMs) off = newOff else break
                }
                return off ?: tzOffsetMin ?: TripFormats.deviceOffsetMin(ms)
            }
            val fmtByOffset = HashMap<Int, java.text.SimpleDateFormat>()
            fun wallClock(ms: Long): String {
                val off = offsetAt(ms)
                val fmt = fmtByOffset.getOrPut(off) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
                        timeZone = TripFormats.offsetTimeZone(off)
                    }
                }
                return fmt.format(ms)
            }
            while (xpos < w) {
                if (xpos > 30 && xpos < w - 30) {
                    canvas.drawLine(xpos.toFloat(), 0f, xpos.toFloat(), h.toFloat(), gridPaint)
                    val stime = wallClock(((xpos / scaleX + minx) * 1000.0).toLong())
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

            // Offset changes (dashed) and local-day changes (dotted): both drawn in
            // the existing grid gray so the plot keeps its small color count, with
            // identically placed rotated labels to the right of each line.
            if (offsetChanges.isNotEmpty() || dateChanges.isNotEmpty()) {
                val tzLine = Paint(gridPaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 7f), 0f)
                }
                val dateLine = Paint(gridPaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 6f), 0f)
                }
                val markerText = Paint(textPaint).apply { textSize = FONT_PX * 0.8f }
                fun marker(ms: Long, text: String, line: Paint) {
                    val tx = ((ms / 1000.0 - minx) * scaleX).toFloat()
                    if (tx < 2f || tx > w - 2f) return
                    canvas.drawLine(tx, 0f, tx, h.toFloat(), line)
                    val cx = (tx + 12f).coerceAtMost(w - 14f)
                    val cy = h / 2f
                    val tw = markerText.measureText(text)
                    val th = markerText.descent() - markerText.ascent()
                    canvas.save()
                    canvas.rotate(-90f, cx, cy)
                    canvas.drawText(text, cx - tw / 2f, cy + th / 2f, markerText)
                    canvas.restore()
                }
                for ((changeMs, newOff) in offsetChanges) marker(changeMs, TripFormats.utcLabel(newOff), tzLine)
                for ((changeMs, dateLabel) in dateChanges) marker(changeMs, dateLabel, dateLine)
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
            for ((ts, spd) in obdSpeeds) {
                if (spd.isNaN()) continue
                val x = ((ts / 1000.0 - minx) * scaleX).toFloat()
                val y = (h - (spd - miny) * scaleY).toFloat()
                canvas.drawCircle(x, y, 3f, redPaint)
            }
            for ((ts, spd) in gpsSpeeds) {
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
