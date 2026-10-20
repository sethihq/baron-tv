package com.barontv.home.volume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.side.SideColors
import com.barontv.home.side.Volume
import com.barontv.home.side.glyph
import kotlin.math.roundToInt

/**
 * The side dock with its Sound view open, on its own: the dock's black frame and hairline, and inside it
 * the one tall bar of side/SoundBar.kt (sidebar.css's .sv-level). The level fills the bar from the bottom
 * in white; the glyph sits at the top, "Volume" and the big number at the bottom, drawn twice, light on
 * the bar and dark on the fill (clipped to the level), so they read on either. Muted, the bar is empty,
 * the glyph crossed out and the caption says Muted.
 *
 * Everything is measured in the prototype's dp scaled to the view's own width (the box is 232 dp wide),
 * not by the context's density: it shows in the accessibility service's window, at the TV's 160 dpi
 * rather than the home's 213. It draws only when the level changes (a 180 ms fill, as SoundBar's).
 */
internal class VolumeBarView(context: Context) : View(context) {

    // Prototype dp in px, set from the width in onSizeChanged.
    private var u = 1f

    private val frame = RectF()
    private val edge = RectF()
    private val bar = RectF()
    private var frameRadius = 0f
    private var edgeRadius = 0f
    private var barRadius = 0f
    private var textX = 0f
    private var numberRise = 0f
    private var captionRise = 0f

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.DOCK_FRAME }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Tokens.FRAME_HAIRLINE
    }
    // The Sound bar's shade while it's in use (focused in the dock): it only ever shows while the volume changes.
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SideColors.LEVEL_FOCUS }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SideColors.LEVEL_FILL }
    private val caption = text(Tokens.TEXT_2, 0.01f)
    private val captionOnFill = text(SideColors.ON_FILL_CAPTION, 0.01f)
    private val number = text(Tokens.TEXT, -0.035f).apply { fontFeatureSettings = "'tnum'" }
    private val numberOnFill = text(SideColors.ON_FILL, -0.035f).apply { fontFeatureSettings = "'tnum'" }

    // The three glyphs (crossed out, low, high), light and dark, made once.
    private val light: Array<Drawable> = Array(3) { context.glyph(Volume.GLYPHS[it], Tokens.TEXT) }
    private val dark: Array<Drawable> = Array(3) { context.glyph(Volume.GLYPHS[it], SideColors.ON_FILL) }

    private val volumeWord = context.getString(R.string.side_volume)
    private val mutedWord = context.getString(R.string.volume_muted)
    private var word = volumeWord
    private var shown = 0
    private var label = "0"

    private var fillFrac = 0f
    private var fillFrom = 0f
    private var fillTo = 0f
    private val fillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 180
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            fillFrac = fillFrom + (fillTo - fillFrom) * it.animatedFraction
            invalidate()
        }
    }

    init {
        // Our own overlay: nothing here for the accessibility tree.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun text(color: Int, tracking: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Fonts.semibold(context)
        this.color = color
        letterSpacing = tracking
    }

    /** Shows [level]; [animate] slides the fill there (the bar is already on screen), else it jumps. */
    fun setLevel(level: VolumeLevel, animate: Boolean) {
        shown = if (level.muted) 0 else level.percent.coerceIn(0, 100)
        label = shown.toString()
        word = if (level.muted) mutedWord else volumeWord
        fillFrom = fillFrac
        fillTo = shown / 100f
        fillAnim.cancel()
        if (animate) fillAnim.start() else fillFrac = fillTo
        invalidate()
    }

    /** Gone from the screen: no fill left running. */
    fun settle() {
        fillAnim.cancel()
        fillFrac = fillTo
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        u = w / VolumePlace.WIDTH
        // The dock's frame: its corners are the bar's 14 plus the 5 padding, its hairline 1 dp just inside.
        frame.set(0f, 0f, w.toFloat(), h.toFloat())
        frameRadius = 19f * u
        val half = 0.5f * u
        edge.set(half, half, w - half, h - half)
        edgeRadius = frameRadius - half
        edgePaint.strokeWidth = u
        val inset = 5f * u
        bar.set(inset, inset, w - inset, h - inset)
        barRadius = 14f * u
        // SoundBar's measures: 12 dp in, a 22 dp glyph; the number's baseline 17.7 dp up, "Volume" at 50.7.
        textX = bar.left + 12f * u
        numberRise = 17.7f * u
        captionRise = 50.7f * u
        caption.textSize = 12f * u
        captionOnFill.textSize = 12f * u
        number.textSize = 34f * u
        numberOnFill.textSize = 34f * u
        val l = textX.roundToInt()
        val t = (bar.top + 12f * u).roundToInt()
        val size = (22f * u).roundToInt()
        for (d in light) d.setBounds(l, t, l + size, t + size)
        for (d in dark) d.setBounds(l, t, l + size, t + size)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(frame, frameRadius, frameRadius, framePaint)
        canvas.drawRoundRect(edge, edgeRadius, edgeRadius, edgePaint)
        canvas.drawRoundRect(bar, barRadius, barRadius, barPaint)
        val g = Volume.glyphIndex(shown)
        drawContent(canvas, light[g], caption, number)
        if (fillFrac > 0f) {
            // The level, and the same content in dark where it covers it.
            val top = bar.bottom - bar.height() * fillFrac
            canvas.save()
            canvas.clipRect(bar.left, top, bar.right, bar.bottom)
            canvas.drawRoundRect(bar, barRadius, barRadius, fillPaint)
            drawContent(canvas, dark[g], captionOnFill, numberOnFill)
            canvas.restore()
        }
    }

    private fun drawContent(canvas: Canvas, glyph: Drawable, captionPaint: Paint, numberPaint: Paint) {
        glyph.draw(canvas)
        canvas.drawText(label, textX, bar.bottom - numberRise, numberPaint)
        canvas.drawText(word, textX, bar.bottom - captionRise, captionPaint)
    }
}
