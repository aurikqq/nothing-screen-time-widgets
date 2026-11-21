package com.aurikqq.noswidgets.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Standalone, density-independent rendering of the supplied Nothing widget references.
 * Images have transparent margins when width != height. Coordinates use a 564-unit square.
 * System serif/sans are fallbacks: pass licensed typefaces to reproduce the original lettering.
 * Screen duration uses the supplied dot typeface; the face is drawn geometrically.
 * This renderer does not collect usage data or request permissions.
 */
object WidgetRenderer {
    data class Palette(
        val background: Int = Color.rgb(34, 26, 24),
        val foreground: Int = Color.rgb(253, 238, 235),
        val inactive: Int = Color.rgb(56, 46, 45),
        val accent: Int = Color.rgb(132, 82, 75),
    )

    data class Fonts(
        val label: Typeface = Typeface.create("sans-serif", Typeface.NORMAL),
        val duration: Typeface = Typeface.create("serif", Typeface.NORMAL),
        val dot: Typeface = Typeface.create("monospace", Typeface.NORMAL),
        val emboldenDot: Boolean = false,
    )

    /** Fractions of the displayed local day, in [0, 1]. Split intervals across midnight. */
    data class Interval(val start: Float, val end: Float)

    data class AwayData(
        val totalMinutes: Long?,
        val awayIntervals: List<Interval> = emptyList(),
        val nowFraction: Float? = null,
        val label: String = "Away",
        val noDataLabel: String = "No data",
        val hourUnit: String = "h",
        val minuteUnit: String = "m",
        val monday: Boolean = false,
        val partial: Boolean = false,
        val displaySidePixels: Float = 564f,
        val displayDensity: Float = 1f,
    )

    data class ScreenData(
        val totalMinutes: Long?,
        val goalMinutes: Long = 360,
        val noDataLabel: String = "No data",
        val overLimit: Boolean = false,
        val partial: Boolean = false,
    )

    enum class ScreenPage { TIME, FACE }

    fun away(
        width: Int,
        height: Int,
        data: AwayData,
        palette: Palette = Palette(),
        fonts: Fonts = Fonts(),
    ): Bitmap = render(width, height, palette) { c, p ->
        val track = RectF(50f, 50f, 514f, 159f)
        p.color = Color.rgb((Color.red(palette.inactive)*0.78f).roundToInt(), (Color.green(palette.inactive)*0.78f).roundToInt(), (Color.blue(palette.inactive)*0.78f).roundToInt())
        c.drawRoundRect(track, 28f, 28f, p)
        if (data.totalMinutes != null) {
            require(data.totalMinutes >= 0) { "totalMinutes must be non-negative" }
            val save = c.save()
            c.clipPath(android.graphics.Path().apply {
                addRoundRect(track, 28f, 28f, android.graphics.Path.Direction.CW)
            })
            p.color = palette.accent
            val trackPixels = (data.displaySidePixels * 464.0 / 564.0).coerceAtLeast(1.0)
            val segments = data.awayIntervals.map {
                com.aurikqq.noswidgets.core.AwayTimeline.Segment(it.start.toDouble(),it.end.toDouble())
            }
            for (bar in com.aurikqq.noswidgets.core.AwayTimeline.stockBars(segments,trackPixels)) {
                val left=50f+464f*bar.start.toFloat()
                val right=50f+464f*bar.end.toFloat()
                val radius=min(2f*data.displayDensity*564f/data.displaySidePixels.coerceAtLeast(1f),(right-left)/2f)
                c.drawRoundRect(RectF(left,50f,right,159f),radius,radius,p)
            }
            c.restoreToCount(save)
            data.nowFraction?.takeIf { it.isFinite() }?.let {
                val x = 50f + 464f * it.coerceIn(0f, 1f)
                p.color = palette.foreground
                p.strokeWidth = 4f
                c.drawLine(x, 183f, x, 292f, p)
            }
        }
        text(c, p, if (data.totalMinutes == null) data.noDataLabel else data.label,
            50f, 374f, 55f, 400f, fonts.label, palette.foreground)
        val duration = data.totalMinutes?.let { "${it / 60}${data.hourUnit} ${it % 60}${data.minuteUnit}" } ?: "—"
        text(c, p, duration, 43f, 496f, 100f, 350f, fonts.duration, palette.foreground)
        if (data.monday) { p.color = palette.accent; c.drawCircle(477f, 455f, 17f, p) }
        else star(c, p, 477f, 455f, 33f, palette.accent)
    }

    fun screen(
        width: Int,
        height: Int,
        data: ScreenData,
        page: ScreenPage = ScreenPage.TIME,
        palette: Palette = Palette(),
        fonts: Fonts = Fonts(),
        transparentBackground: Boolean = false,
        drawEyes: Boolean = true,
    ): Bitmap = render(width, height, screenPalette(data, palette), !transparentBackground) { c, p ->
        data.totalMinutes?.let { require(it >= 0) { "totalMinutes must be non-negative" } }
        require(data.goalMinutes > 0) { "goalMinutes must be positive" }
        if (page == ScreenPage.FACE) {
            phone(c, p, palette, data.totalMinutes != null, data.overLimit, drawEyes)
            if (data.totalMinutes == null) {
                text(c, p, data.noDataLabel, 50f, 486f, 28f, 464f, fonts.label, palette.foreground)
            }
        } else {
            val fraction = data.totalMinutes?.toDouble()?.div(data.goalMinutes)?.coerceIn(0.0, 1.0) ?: 0.0
            // Column-major order matches the reference: six complete columns are lit.
            val lit = (fraction * 20).roundToInt()
            for (column in 0 until 20) for (row in 0 until 3) {
                p.color = if (column < lit) palette.foreground else palette.inactive
                c.drawCircle(62f + column * 23.2f, 62f + row * 23.2f, 11.3f, p)
            }
            if (data.totalMinutes == null) {
                text(c, p, data.noDataLabel, 58f, 486f, 40f, 440f, fonts.label, palette.foreground)
            } else {
                p.isFakeBoldText = fonts.emboldenDot
                dotDuration(c, p, data.totalMinutes, palette.foreground, fonts.dot)
                p.isFakeBoldText = false
            }
        }
    }

    /** Separate transparent eye layer: ViewFlipper in the host performs the blink. */
    fun screenEyes(width: Int, height: Int, palette: Palette = Palette()): Bitmap =
        render(width, height, palette, false) { c, p ->
            p.color = palette.foreground
            c.drawCircle(243f, 263f, 6f, p)
            c.drawCircle(321f, 263f, 6f, p)
        }

    /** One persistent card background; screen pages can be rendered as transparent content. */
    fun screenBackground(width: Int, height: Int, data: ScreenData, palette: Palette = Palette()): Bitmap =
        render(width, height, screenPalette(data, palette)) { _, _ -> }

    private fun screenPalette(data: ScreenData, palette: Palette): Palette =
        if (data.overLimit) palette.copy(background = Color.rgb(215, 25, 33)) else palette

    private inline fun render(
        width: Int,
        height: Int,
        palette: Palette,
        drawBackground: Boolean = true,
        draw: (Canvas, Paint) -> Unit,
    ): Bitmap {
        require(width > 0 && height > 0) { "Bitmap dimensions must be positive" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val scale = min(width, height) / 564f
        c.translate((width - 564f * scale) / 2f, (height - 564f * scale) / 2f)
        c.scale(scale, scale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (drawBackground) {
            p.color = palette.background
            c.drawRoundRect(RectF(0f, 0f, 564f, 564f), 87f, 87f, p)
        }
        draw(c, p)
        return bitmap
    }

    private fun text(
        c: Canvas, p: Paint, value: String, x: Float, baseline: Float,
        size: Float, maxWidth: Float, font: Typeface, color: Int,
    ) {
        p.color = color
        p.typeface = font
        p.textSize = size
        val measured = p.measureText(value)
        if (measured > maxWidth && measured > 0f) p.textSize *= maxWidth / measured
        c.drawText(value, x, baseline, p)
    }

    private fun star(c: Canvas, p: Paint, x: Float, y: Float, radius: Float, color: Int) {
        p.color = color
        p.strokeWidth = 5f
        for (ray in 0 until 8) {
            val a = ray * Math.PI / 4
            c.drawLine(x, y, x + cos(a).toFloat() * radius, y + sin(a).toFloat() * radius, p)
        }
    }

    private fun phone(c: Canvas, p: Paint, colors: Palette, hasData: Boolean, sad: Boolean, drawEyes: Boolean) {
        p.color = if (hasData) colors.foreground else colors.inactive
        val left = 204f
        val top = 146f
        val step = 13f
        fun dot(x: Float, y: Float) = c.drawCircle(x, y, 6f, p)
        // Rounded corners: side and horizontal rows omit their corner point.
        for (i in 1..11) {
            dot(left + i * step, top)
            dot(left + i * step, top + 21 * step)
        }
        for (i in 1..20) {
            dot(left, top + i * step)
            dot(left + 12 * step, top + i * step)
        }
        for (i in 5..7) dot(left + i * step, top + 2 * step)
        if (hasData) {
            if (drawEyes) {
                dot(left + 3 * step, top + 9 * step)
                dot(left + 9 * step, top + 9 * step)
            }
            for (i in 9..12) dot(left + 6 * step, top + i * step)
            dot(left + 5 * step, top + 12 * step)
            dot(left + 3 * step, top + (if (sad) 16 else 15) * step)
            dot(left + 9 * step, top + (if (sad) 16 else 15) * step)
            for (i in 4..8) dot(left + i * step, top + (if (sad) 15 else 16) * step)
        }
    }

    private fun dotDuration(c: Canvas, p: Paint, minutes: Long, color: Int, font: Typeface) {
        val hours = (minutes / 60).toString()
        val mins = (minutes % 60).toString()
        val numeralSize = 140f
        val markerSize = 60f
        val markerGap = 10f
        val groupGap = 14f
        p.typeface = font
        p.textSize = numeralSize
        val hourWidth = p.measureText(hours)
        val minuteWidth = p.measureText(mins)
        p.textSize = markerSize
        val hWidth = p.measureText("H")
        val mWidth = p.measureText("M")
        val contentWidth = hourWidth + minuteWidth + hWidth + mWidth + 2 * markerGap + groupGap
        val save = c.save()
        c.translate(58f, 486f)
        val fit = min(1f, 448f / contentWidth)
        c.scale(fit, fit)
        p.color = color
        p.textSize = numeralSize
        c.drawText(hours, 0f, 0f, p)
        var x = hourWidth + markerGap
        p.textSize = markerSize
        c.drawText("H", x, 0f, p)
        x += hWidth + groupGap
        p.textSize = numeralSize
        c.drawText(mins, x, 0f, p)
        x += minuteWidth + markerGap
        p.textSize = markerSize
        c.drawText("M", x, 0f, p)
        c.restoreToCount(save)
    }
}
