package com.barontv.home.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import com.barontv.home.core.dpi
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clock: Bento (bento.css, bento.js). A black frame with a hairline holding four tiles: a cream watch dial
 * with an orange second hand, the time under a greeting, a square the colour of the sky now with the
 * temperature, and a line that reads like a sentence (the weekday in calendar red, the date, then what the
 * weather does next). Each tile is its own view, so the dial's tick each second redraws only the dial.
 */
class BentoView(context: Context) : ViewGroup(context) {
    val dial = DialView(context)
    val time = TimeTileView(context)
    val sky = SkyTileView(context)
    val line = LineTileView(context)

    private val pad = context.dp(5f)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.DOCK_FRAME }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = Tokens.FRAME_HAIRLINE
    }
    private val frame = RectF()
    private val hair = RectF()

    init {
        setWillNotDraw(false)
        addView(dial)
        addView(time)
        addView(sky)
        addView(line)
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val square = context.dp(46f)
        val inner = measuredWidth - 2 * pad
        measureExactly(dial, square, square)
        measureExactly(sky, square, square)
        measureExactly(time, inner - 2 * square - 2 * pad, square)
        measureExactly(line, inner, measuredHeight - 3 * pad - square)
    }

    private fun measureExactly(v: View, w: Float, h: Float) {
        v.measure(MeasureSpec.makeMeasureSpec(w.roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.roundToInt(), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // grid-template-columns: 46px 1fr 46px; rows 46px 28px; gap and padding 5.
        val p = pad.roundToInt()
        dial.layout(p, p, p + dial.measuredWidth, p + dial.measuredHeight)
        val tx = dial.right + p
        time.layout(tx, p, tx + time.measuredWidth, p + time.measuredHeight)
        val sx = time.right + p
        sky.layout(sx, p, sx + sky.measuredWidth, p + sky.measuredHeight)
        val ly = dial.bottom + p
        line.layout(p, ly, p + line.measuredWidth, ly + line.measuredHeight)
        frame.set(0f, 0f, width.toFloat(), height.toFloat())
        hair.set(frame)
        hair.inset(context.dp(0.5f), context.dp(0.5f))
    }

    override fun onDraw(canvas: Canvas) {
        // The frame's radius is the tiles' 14 plus the padding, so the corners nest.
        val r = context.dp(14f) + pad
        canvas.drawRoundRect(frame, r, r, framePaint)
        canvas.drawRoundRect(hair, r - context.dp(0.5f), r - context.dp(0.5f), hairPaint)
    }
}

/** The tiles' shared surface: oklch(20%), radius 14. */
abstract class BentoTile(context: Context) : View(context) {
    protected val tileRadius = context.dp(14f)
    protected val tile = RectF()
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.TILE }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        tile.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    protected fun drawTile(canvas: Canvas, paint: Paint = tilePaint) {
        canvas.drawRoundRect(tile, tileRadius, tileRadius, paint)
    }
}

/**
 * The watch dial: a cream face, twelve dots (bigger at the quarters), dark hour and minute hands, an
 * orange second hand and cap, drawn from the prototype's 100-unit SVG at 40 dp. The second hand ticks
 * with a 160 ms ease and keeps counting past 59, so the tick from 59 to 0 doesn't spin it backwards.
 */
class DialView(context: Context) : BentoTile(context) {
    private val unit = context.dp(40f) / 100f
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Oklab.oklch(0.94, 0.018, 85.0) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Oklab.oklch(0.58, 0.01, 85.0) }
    private fun hand(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = width * unit
        this.color = color
    }
    private val dark = Oklab.oklch(0.19, 0.0, 0.0)
    private val orange = Oklab.oklch(0.72, 0.18, 50.0)
    private val hourPaint = hand(7f, dark)
    private val minutePaint = hand(4.5f, dark)
    private val secondPaint = hand(2.2f, orange)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }

    private var hourAngle = 0f
    private var minuteAngle = 0f
    private val second = Tween(0f)
    private var lastSecond = -1

    /** The next tick sets the hand straight to its second instead of easing round to it. */
    fun reset() {
        lastSecond = -1
    }

    fun tick(hour: Int, minute: Int, sec: Int) {
        hourAngle = (hour % 12) * 30f + minute * 0.5f
        minuteAngle = minute * 6f + sec * 0.1f
        if (lastSecond < 0) {
            second.snap(sec * 6f)
        } else if (sec != lastSecond) {
            var goal = second.target + ((sec - lastSecond + 60) % 60) * 6f
            if (goal >= 720f) {
                // Same place a turn back, so the number never grows without end.
                second.snap(second.value - 360f)
                goal -= 360f
            }
            second.to(goal, 160, HomeMotion.TICK)
        }
        lastSecond = sec
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawTile(canvas)
        val moving = second.step()
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 49 * unit, facePaint)
        for (i in 0 until 12) {
            val a = i * Math.PI / 6
            canvas.drawCircle(cx + 38 * unit * sin(a).toFloat(), cy - 38 * unit * cos(a).toFloat(), (if (i % 3 == 0) 3.8f else 2.4f) * unit, dotPaint)
        }
        drawHand(canvas, cx, cy, hourAngle, 0f, 23f, hourPaint)
        drawHand(canvas, cx, cy, minuteAngle, 0f, 35f, minutePaint)
        drawHand(canvas, cx, cy, second.value, 11f, 38f, secondPaint)
        canvas.drawCircle(cx, cy, 4.2f * unit, capPaint)
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, angle: Float, tail: Float, length: Float, paint: Paint) {
        val save = canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawLine(cx, cy + tail * unit, cx, cy - length * unit, paint)
        canvas.restoreToCount(save)
    }
}

/** The greeting (12, secondary) over the time (24 semibold, tabular) and am or pm, centred in the tile. */
class TimeTileView(context: Context) : BentoTile(context) {
    private val greetPaint = context.textPaint(Fonts.medium(context), 12f, Tokens.TEXT_2, -0.01f)
    private val timePaint = context.textPaint(Fonts.semibold(context), 24f, Tokens.TEXT, -0.03f, tabular = true)
    private val ampmPaint = context.textPaint(Fonts.semibold(context), 12f, Tokens.TEXT_2)

    private var hour = -1
    private var greeting = ""
    private var clock = ""
    private var ampm = ""
    private var greetBaseline = 0f
    private var clockBaseline = 0f
    private var ampmX = 0f

    fun setTime(hour24: Int, minute: Int) {
        val text = "${if (hour24 % 12 == 0) 12 else hour24 % 12}:${minute.toString().padStart(2, '0')}"
        if (text == clock && hour24 == hour) return
        hour = hour24
        clock = text
        ampm = if (hour24 < 12) "am" else "pm"
        contentDescription = "$text $ampm"
        fit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        if (width == 0 || hour < 0) return
        val c = context
        // Never cut: if the whole greeting doesn't fit, say it shorter (bento.js fitGreeting).
        val (full, short) = greetingFor(hour)
        val room = width - c.dp(8f) - c.dp(4f)
        greeting = if (greetPaint.measureText(full) <= room) full else short
        val greetLine = c.dp(12f * 1.15f)
        val timeLine = c.dp(24f * 1.05f)
        val top = (height - greetLine - timeLine) / 2f
        greetBaseline = baselineIn(greetPaint, top, greetLine)
        clockBaseline = baselineIn(timePaint, top + greetLine, timeLine)
        ampmX = c.dp(8f) + timePaint.measureText(clock) + c.dp(2f)
    }

    override fun onDraw(canvas: Canvas) {
        drawTile(canvas)
        val x = context.dp(8f)
        canvas.drawText(greeting, x, greetBaseline, greetPaint)
        canvas.drawText(clock, x, clockBaseline, timePaint)
        canvas.drawText(ampm, ampmX, clockBaseline, ampmPaint)
    }

    companion object {
        fun greetingFor(hour: Int): Pair<String, String> = when (hour) {
            in 5..11 -> "Good morning" to "Morning"
            in 12..16 -> "Good afternoon" to "Afternoon"
            in 17..21 -> "Good evening" to "Evening"
            else -> "Good night" to "Night"
        }
    }
}

/** The sky right now: a 150 degree gradient in its colours, the glyph top left, the temperature under it. */
class SkyTileView(context: Context) : BentoTile(context) {
    private val glyphSize = context.dpi(15f)
    private val tempPaint = context.textPaint(Fonts.semibold(context), 17f, Tokens.TEXT, -0.02f, tabular = true)
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var glyph: Bitmap = Glyphs.mask(context, R.drawable.ph_cloud, glyphSize)
    private var temp = "--°"
    private var sky = Sky.NONE
    private var tempBaseline = 0f

    fun setWeather(reading: WeatherReading?) {
        sky = Weather.sky(reading)
        temp = if (reading != null) "${reading.temp}°" else "--°"
        glyph = Glyphs.mask(context, Weather.glyph(reading?.icon), glyphSize)
        contentDescription = if (reading != null) "${reading.temp} degrees, ${reading.text}" else null
        paintSky()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // align-content: space-between, padding 6 top and 5 bottom: the temperature's line ends 5 up.
        val line = context.dp(17f)
        tempBaseline = baselineIn(tempPaint, h - context.dp(5f) - line, line)
        paintSky()
    }

    private fun paintSky() {
        if (width == 0) return
        val (top, bottom, ink) = SKY_COLORS.getValue(sky)
        // linear-gradient(150deg, ...): along a line through the centre, as long as CSS makes it.
        val w = width.toFloat()
        val h = height.toFloat()
        val a = Math.toRadians(150.0)
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f
        skyPaint.shader = LinearGradient(w / 2 - dx * half, h / 2 - dy * half, w / 2 + dx * half, h / 2 + dy * half, top, bottom, Shader.TileMode.CLAMP)
        tempPaint.color = ink
        glyphPaint.color = ink
    }

    override fun onDraw(canvas: Canvas) {
        drawTile(canvas, skyPaint)
        canvas.drawBitmap(glyph, context.dp(7f), context.dp(6f), glyphPaint)
        canvas.drawText(temp, context.dp(7f), tempBaseline, tempPaint)
    }

    companion object {
        private val WHITE_INK = Oklab.oklch(0.98, 0.0, 0.0)

        /** bento.css's --sky-1, --sky-2 and --sky-ink for each sky. */
        val SKY_COLORS: Map<Sky, Triple<Int, Int, Int>> = mapOf(
            Sky.NONE to Triple(Oklab.oklch(0.36, 0.0, 0.0), Oklab.oklch(0.26, 0.0, 0.0), Tokens.TEXT),
            Sky.SUN to Triple(Oklab.oklch(0.89, 0.14, 92.0), Oklab.oklch(0.78, 0.16, 60.0), Oklab.oklch(0.27, 0.06, 55.0)),
            Sky.PARTLY to Triple(Oklab.oklch(0.85, 0.06, 225.0), Oklab.oklch(0.70, 0.08, 240.0), Oklab.oklch(0.24, 0.04, 245.0)),
            Sky.CLOUD to Triple(Oklab.oklch(0.68, 0.02, 250.0), Oklab.oklch(0.54, 0.025, 255.0), WHITE_INK),
            Sky.RAIN to Triple(Oklab.oklch(0.62, 0.09, 228.0), Oklab.oklch(0.46, 0.10, 245.0), WHITE_INK),
            Sky.STORM to Triple(Oklab.oklch(0.50, 0.12, 295.0), Oklab.oklch(0.35, 0.10, 285.0), WHITE_INK),
            Sky.NIGHT to Triple(Oklab.oklch(0.43, 0.11, 278.0), Oklab.oklch(0.27, 0.08, 272.0), WHITE_INK),
        )
    }
}

/**
 * The sentence line: "TUE 20 · (glyph) Drizzle from 3 pm". The weekday in calendar red, the date and the
 * strong words white and semibold, the rest secondary; 5 apart, centred in the 28 tall tile. A long
 * forecast loses its glyph first, then steps down to 11 (bento.js drawLine). Laid out when it changes;
 * onDraw only draws.
 */
class LineTileView(context: Context) : BentoTile(context) {
    private val wdayPaint = context.textPaint(Fonts.bold(context), 12f, Tokens.CALENDAR_RED, 0.04f)
    private val strong = arrayOf(
        context.textPaint(Fonts.semibold(context), 12f, Tokens.TEXT, 0.012f),
        context.textPaint(Fonts.semibold(context), 11f, Tokens.TEXT, 0.012f),
    )
    private val plain = arrayOf(
        context.textPaint(Fonts.medium(context), 12f, Tokens.TEXT_2, 0.012f),
        context.textPaint(Fonts.medium(context), 11f, Tokens.TEXT_2, 0.012f),
    )
    private val sep = arrayOf(
        context.textPaint(Fonts.medium(context), 12f, Tokens.TEXT_2, 0.012f).apply { alpha = (255 * 0.6f).roundToInt() },
        context.textPaint(Fonts.medium(context), 11f, Tokens.TEXT_2, 0.012f).apply { alpha = (255 * 0.6f).roundToInt() },
    )
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Tokens.TEXT }
    private val glyphSize = context.dpi(13f)

    private class Run(val text: String, val paint: TextPaint, var x: Float = 0f, var baseline: Float = 0f)

    private var runs: List<Run> = emptyList()
    private var glyph: Bitmap? = null
    private var glyphX = 0f
    private var glyphY = 0f

    private var weekday = ""
    private var date = 0
    private var reading: WeatherReading? = null

    fun setLine(weekday: String, date: Int, reading: WeatherReading?) {
        this.weekday = weekday
        this.date = date
        this.reading = reading
        layoutRuns()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutRuns()
    }

    private fun layoutRuns() {
        if (width == 0 || weekday.isEmpty()) return
        val next = reading?.let { Weather.next(it) }
        val icon = next?.let { Glyphs.mask(context, Weather.glyph(it.icon), glyphSize) }
        // As it reads, then without the glyph, then a size down (and clipped if even that's too long).
        val tries = listOf(0 to icon, 0 to null, 1 to null)
        for ((k, attempt) in tries.withIndex()) {
            val (tight, g) = attempt
            if (k == 1 && icon == null) continue
            val built = build(next, tight)
            if (place(built, g) || k == tries.lastIndex) {
                runs = built
                glyph = g
                contentDescription = built.joinToString(" ") { it.text }
                return
            }
        }
    }

    private fun build(next: WeatherNext?, tight: Int): List<Run> {
        val list = mutableListOf(Run(weekday, wdayPaint), Run(date.toString(), strong[tight]), Run("·", sep[tight]))
        if (next == null) list += Run("Bengaluru", plain[tight])
        else for ((text, isStrong) in next.words) list += Run(text, if (isStrong) strong[tight] else plain[tight])
        return list
    }

    /** Places the runs (and the glyph after the separator); true if it all fits in the tile. */
    private fun place(list: List<Run>, icon: Bitmap?): Boolean {
        val c = context
        val gap = c.dp(5f)
        var x = c.dp(10f)
        for ((i, run) in list.withIndex()) {
            // line-height 1: each run's box is its own font size tall, centred in the tile.
            val size = run.paint.textSize
            run.x = x
            run.baseline = baselineIn(run.paint, (height - size) / 2f, size)
            x += run.paint.measureText(run.text) + gap
            if (i == 2 && icon != null) {
                glyphX = x
                glyphY = (height - glyphSize) / 2f
                x += glyphSize + gap
            }
        }
        return x - gap <= width - c.dp(10f)
    }

    override fun onDraw(canvas: Canvas) {
        drawTile(canvas)
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width - context.dp(10f), height.toFloat())
        // An index loop: no iterator per frame.
        for (i in runs.indices) {
            val run = runs[i]
            canvas.drawText(run.text, run.x, run.baseline, run.paint)
        }
        glyph?.let { canvas.drawBitmap(it, glyphX, glyphY, glyphPaint) }
        canvas.restoreToCount(save)
    }
}
