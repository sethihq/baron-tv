package com.barontv.home.side

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.View
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.roundToInt

/**
 * One of the dock's tiles (bento.css's .bento-tile): the glyph top left, the label bottom left, on a
 * rounded square. Focus only turns it lighter (no lift, no shadow); a press dips it to 0.97 for a moment.
 * An input with nothing plugged in is dimmed; one that's connected gets a small green dot.
 */
internal class SideTile(context: Context) : View(context) {

    private fun dp(v: Float) = context.dp(v)

    private val pad = dp(9f)
    private val glyphSize = dp(22f).roundToInt()
    private val radius = dp(14f)
    private val dotR = dp(3f)
    private val dotInset = dp(13f) // the dot's centre: 10 dp in from the corner, plus its radius

    // The label's line is 14.95 dp (13 at 1.15) on the bottom padding, its baseline 12 dp into it.
    private val labelRise = dp(9f + 14.95f - 12f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.LIVE_GREEN }
    private val label = Type.paint(context, Fonts.medium(context), 13f, Tokens.TEXT)
    private val rect = RectF()

    private var text = ""
    private var shown = ""
    private var glyphRes = 0
    private var glyphPlain: Drawable? = null
    private var glyphIdle: Drawable? = null

    /** Nothing plugged in: glyph and label in the quieter grey at 55% (80% when focused). */
    var idle = false
        private set

    /** Something plugged in: the green dot. */
    var live = false
        private set

    private var focusMix = 0f
    private var mixFrom = 0f
    private var mixTo = 0f
    private val focusFade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 240
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            focusMix = mixFrom + (mixTo - mixFrom) * it.animatedFraction
            invalidate()
        }
    }

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        isClickable = true
    }

    fun bind(label: String, glyph: Int, idle: Boolean, live: Boolean) {
        if (glyph != glyphRes) {
            glyphRes = glyph
            glyphPlain = context.glyph(glyph, Tokens.TEXT)
            glyphIdle = context.glyph(glyph, Tokens.TEXT_2)
            placeGlyphs()
        }
        this.idle = idle
        this.live = live
        text = label
        contentDescription = label
        fitLabel()
        invalidate()
    }

    val labelText: String get() = text

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeGlyphs()
        fitLabel()
    }

    private fun placeGlyphs() {
        val p = pad.roundToInt()
        glyphPlain?.setBounds(p, p, p + glyphSize, p + glyphSize)
        glyphIdle?.setBounds(p, p, p + glyphSize, p + glyphSize)
    }

    private fun fitLabel() {
        val room = width - 2 * pad
        shown = if (room <= 0f) text else TextUtils.ellipsize(text, label, room, TextUtils.TruncateAt.END).toString()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        mixFrom = focusMix
        mixTo = if (gainFocus) 1f else 0f
        focusFade.cancel()
        if (isAttachedToWindow) focusFade.start() else focusMix = mixTo
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // The press: down to 0.97 while held, back on release, 160 ms each way.
        val s = if (isPressed) 0.97f else 1f
        if (scaleX != s) animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(Tokens.EASE_OUT).start()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = SideColors.mix(Tokens.TILE, Tokens.TILE_FOCUS, focusMix)
        canvas.drawRoundRect(rect, radius, radius, bg)

        // An idle input's content: the quieter grey at 55%, or the text colour at 80% when focused.
        val focused = isFocused
        val g = if (idle && !focused) glyphIdle else glyphPlain
        val a = if (!idle) 255 else if (focused) 204 else 140
        g?.alpha = a
        g?.draw(canvas)
        label.color = if (idle && !focused) Tokens.TEXT_2 else Tokens.TEXT
        label.alpha = a
        canvas.drawText(shown, pad, height - labelRise, label)

        if (live) canvas.drawCircle(width - dotInset, dotInset, dotR, dot)
    }
}
