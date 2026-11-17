package com.example.nothingwidgets.weekly

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

/** Drawing is kept in reference coordinates; no bitmap of the reference is used. */
internal class WeeklyAwayView(
    context: Context,
    private val monday: LocalDate,
    private val minutes: List<Long?>,
    private val selected: LocalDate,
    private val partial: Boolean,
    private val dismiss: () -> Unit
) : FrameLayout(context) {
    private val panel = RectF()
    private val painter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Color.rgb(245, 245, 249)
    private val muted = Color.rgb(212, 214, 227)
    private var unit = 1f
    private val close = ImageButton(context).apply {
        contentDescription = "Close weekly away time"
        setImageDrawable(CloseDrawable())
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.argb(45, 238, 217, 222))
        }
        setPadding(0, 0, 0, 0)
        setOnClickListener { dismiss() }
    }

    init {
        setWillNotDraw(false)
        addView(close, LayoutParams(1, 1))
        val measurements = minutes.mapIndexed { index, value ->
            "${monday.plusDays(index.toLong())}: ${value?.let(::duration) ?: "no data"}"
        }.joinToString(". ")
        contentDescription = "Weekly away time. ${if (partial) "Partial data. " else ""}$measurements"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scale = min(measuredWidth / 1080f, measuredHeight / 1900f)
        val size = max(126f * scale, 48f * resources.displayMetrics.density).roundToInt()
        val exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        close.measure(exact, exact)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = min(w / 1080f, h / 1900f)
        val panelWidth = 954f * unit
        val panelHeight = 1224f * unit
        panel.set((w - panelWidth) / 2f, (h - panelHeight) / 2f,
            (w + panelWidth) / 2f, (h + panelHeight) / 2f)
        val closeSize = max(126f * unit, 48f * resources.displayMetrics.density).roundToInt()
        val centerY = min(h - closeSize / 2f - 64f * unit, panel.bottom + 324f * unit)
        close.layoutParams = (close.layoutParams as LayoutParams).apply {
            width = closeSize; height = closeSize
            leftMargin = (w - closeSize) / 2
            topMargin = (centerY - closeSize / 2f).roundToInt()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(panel.left, panel.top)
        canvas.scale(unit, unit)
        val bounds = RectF(0f, 0f, 954f, 1224f)
        painter.shader = LinearGradient(0f, 0f, 760f, 1224f,
            intArrayOf(Color.rgb(30, 50, 100), Color.rgb(62, 73, 111), Color.rgb(82, 85, 112)),
            floatArrayOf(0f, .57f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bounds, 58f, 58f, painter)
        painter.shader = null
        text(canvas, "Weekly away time", 42f, 83f, 41f, white, Typeface.MONOSPACE, true)
        star(canvas, 886f, 69f)
        val known = minutes.filterNotNull()
        val total = known.sum()
        text(canvas, if (known.isEmpty()) "—" else duration(total, compact = true),
            42f, 211f, 72f, white, Typeface.create("serif", Typeface.NORMAL))
        val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        text(canvas, "${monday.format(formatter)} – ${monday.plusDays(6).format(formatter)}",
            42f, 270f, 36f, muted)
        if (partial && known.isNotEmpty()) text(canvas, "Partial data", 42f, 311f, 24f, muted)
        painter.color = Color.argb(110, 25, 36, 67)
        canvas.drawRoundRect(RectF(32f, 338f, 922f, 423f), 23f, 23f, painter)
        for (i in 0..11) {
            val angle = i * Math.PI / 6
            painter.color = white
            canvas.drawCircle(71f + cos(angle).toFloat() * 14f, 380f + sin(angle).toFloat() * 14f, 2.3f, painter)
        }
        text(canvas, "Day average", 111f, 397f, 41f, white)
        val average = if (known.isEmpty()) "—" else duration(total / known.size)
        text(canvas, average, 900f, 396f, 40f, white, Typeface.MONOSPACE, true, Paint.Align.RIGHT)
        drawChart(canvas)
        if (selected.dayOfWeek == java.time.DayOfWeek.MONDAY && monday == selected.minusWeeks(1)) {
            val firstMax = minutes.withIndex().filter { it.value != null }.maxByOrNull { it.value!! }
            val message = if (firstMax == null) "Best day: no data" else {
                val day = monday.plusDays(firstMax.index.toLong()).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
                "${if (partial) "Best known day" else "Best day"}: $day · ${duration(firstMax.value!!)}"
            }
            text(canvas, message, 42f, 1120f, 26f, muted)
        }
        canvas.restore()
    }

    private fun drawChart(canvas: Canvas) {
        val left = 42f
        val top = 550f
        val bottom = 990f
        val cell = 124.2f
        painter.strokeWidth = 1f
        painter.color = Color.argb(125, 223, 225, 236)
        painter.pathEffect = DashPathEffect(floatArrayOf(3f, 10f), 0f)
        for (index in 0..7) canvas.drawLine(left + index * cell, top, left + index * cell, bottom, painter)
        painter.pathEffect = null
        val maximum = max(60L, minutes.filterNotNull().maxOrNull() ?: 0L)
        minutes.forEachIndexed { index, value ->
            val x = left + (index + .5f) * cell
            if (value != null) {
                val y = bottom - 16f - (value.toFloat() / maximum) * (bottom - top - 144f)
                painter.color = Color.WHITE
                canvas.drawCircle(x, y, 5.2f, painter)
            } else if (!monday.plusDays(index.toLong()).isAfter(selected)) {
                text(canvas, "—", x, bottom - 8f, 22f, muted, align = Paint.Align.CENTER)
            }
            val date = monday.plusDays(index.toLong())
            text(canvas, date.dayOfMonth.toString(), x, 1033f, 29f,
                if (date == selected) white else muted, bold = date == selected, align = Paint.Align.CENTER)
        }
        if (minutes.all { it == null }) {
            text(canvas, "No away time data", 477f, 730f, 36f, white, align = Paint.Align.CENTER)
            text(canvas, "No recorded observations for this week", 477f, 778f, 28f, muted, align = Paint.Align.CENTER)
        }
    }

    private fun star(canvas: Canvas, x: Float, y: Float) {
        painter.color = white; painter.strokeWidth = 3.8f; painter.strokeCap = Paint.Cap.SQUARE
        for (index in 0..3) {
            val angle = index * Math.PI / 4
            val dx = cos(angle).toFloat() * 22f
            val dy = sin(angle).toFloat() * 22f
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, painter)
        }
        painter.strokeCap = Paint.Cap.BUTT
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                     face: Typeface = Typeface.DEFAULT, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        painter.color = color; painter.textSize = size; painter.textAlign = align
        painter.typeface = if (bold) Typeface.create(face, Typeface.BOLD) else face
        painter.style = Paint.Style.FILL
        canvas.drawText(value, x, y, painter)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && !panel.contains(event.x, event.y)) {
            performClick(); dismiss()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private class CloseDrawable : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(244, 242, 244); strokeWidth = 3.5f; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val centerX = bounds.exactCenterX(); val centerY = bounds.exactCenterY()
            val radius = bounds.width() * .135f
            paint.strokeWidth = bounds.width() * .027f
            canvas.drawLine(centerX - radius, centerY - radius, centerX + radius, centerY + radius, paint)
            canvas.drawLine(centerX - radius, centerY + radius, centerX + radius, centerY - radius, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

private fun duration(minutes: Long, compact: Boolean = false): String =
    if (compact) "${minutes / 60}hr ${minutes % 60}min" else "${minutes / 60} hr ${minutes % 60} min"
