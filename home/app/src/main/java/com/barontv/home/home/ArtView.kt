package com.barontv.home.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picture that takes focus the way the prototype's posters and app tiles do ("only colour change is
 * fine"): nothing grows, lifts, casts a shadow or moves. Focus fades in a fine ring, 1 dp of soft white
 * 2 dp outside the art (200 ms); OK dips the art to 0.97 for a moment (160 ms), as a press does.
 *
 * The view's bounds include [ringSpace] round the art, so the ring is never drawn outside them. The art is
 * a bitmap shader in a rounded rectangle, cropped to fill as object-fit: cover: one anti-aliased draw, no
 * clipping. Subclasses place the art and draw what's under or over it.
 */
abstract class ArtView(context: Context) : View(context) {

    /** Room for the ring round the art: its 2 dp offset and 1 dp stroke. */
    val ringSpace = context.dp(3f)

    protected val art = RectF()
    protected var radius = 0f

    /** The ring fades with focus over 200 ms, the rest of the selected state (colours) over 240 ms. */
    protected val ring = Tween(0f)
    protected val focus = Tween(0f)
    private val press = Tween(1f)

    private var bitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
    }
    private val ringRect = RectF()
    private val ringOffset = context.dp(2.5f)

    /** What OK (or a click) does. */
    var onPress: (() -> Unit)? = null

    /** The art's vivid colour, for the glow ([VividColor.FALLBACK] while there's none). */
    var glowColor: Int = VividColor.FALLBACK

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
    }

    val hasArt: Boolean get() = bitmap != null

    fun setArt(art: Bitmap?) {
        if (art === bitmap) return
        bitmap = art
        bitmapPaint.shader = art?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        fitArt()
        invalidate()
    }

    /** Places the art at [left], [top] (px, in this view), [width] x [height], with corners of [corner]. */
    protected fun placeArt(left: Float, top: Float, width: Float, height: Float, corner: Float) {
        art.set(left, top, left + width, top + height)
        radius = corner
        ringRect.set(art)
        ringRect.inset(-ringOffset, -ringOffset)
        fitArt()
    }

    private fun fitArt() {
        val b = bitmap ?: return
        if (art.isEmpty) return
        val scale = max(art.width() / b.width, art.height() / b.height)
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(art.left + (art.width() - b.width * scale) / 2f, art.top + (art.height() - b.height * scale) / 2f)
        bitmapPaint.shader?.setLocalMatrix(shaderMatrix)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        ring.to(if (gainFocus) 1f else 0f, 200)
        focus.to(if (gainFocus) 1f else 0f, 240)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isConfirm(keyCode)) {
            if (event.repeatCount == 0) setDipped(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isConfirm(keyCode)) {
            setDipped(false)
            if (!event.isCanceled) performClick()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        setDipped(pressed)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPress?.invoke()
        return true
    }

    private fun setDipped(dipped: Boolean) {
        press.to(if (dipped) 0.97f else 1f, 160)
        invalidate()
    }

    private fun isConfirm(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A

    override fun onDraw(canvas: Canvas) {
        val now = HomeMotion.now()
        var moving = ring.step(now)
        moving = focus.step(now) || moving
        moving = press.step(now) || moving
        val scale = press.value
        val save = canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, art.centerX(), art.centerY())
        val r = ring.value
        if (r > 0f) {
            ringPaint.color = (((Tokens.FOCUS_RING ushr 24) * r).roundToInt() shl 24) or (Tokens.FOCUS_RING and 0xFFFFFF)
            canvas.drawRoundRect(ringRect, radius + ringOffset, radius + ringOffset, ringPaint)
        }
        if (bitmap != null) canvas.drawRoundRect(art, radius, radius, bitmapPaint) else drawEmptyArt(canvas, focus.value)
        drawOverArt(canvas)
        canvas.restoreToCount(save)
        drawUnderArt(canvas, focus.value)
        if (moving) postInvalidateOnAnimation()
    }

    /** The art's place while there's no picture. */
    protected abstract fun drawEmptyArt(canvas: Canvas, focused: Float)

    /** Drawn on the art, dipping with it (a tile's hairline). */
    protected open fun drawOverArt(canvas: Canvas) {}

    /** Drawn under the art, not dipping (a poster's title and details). */
    protected open fun drawUnderArt(canvas: Canvas, focused: Float) {}
}
