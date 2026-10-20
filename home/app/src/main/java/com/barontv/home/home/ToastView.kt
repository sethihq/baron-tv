package com.barontv.home.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.View
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * The prototype's toast (app.css .toast): a pill of the panel grey 20 dp above the bottom edge, centred,
 * 10 by 20 of padding round one line of body text. It fades in over 180 ms, stays 1.6 s from the moment
 * it's asked for, and fades out; a new message replaces the old one and restarts the clock.
 */
class ToastView(context: Context) : View(context) {
    private val textPaint = context.textPaint(Fonts.medium(context), Tokens.T_BODY, Tokens.TEXT, -0.006f)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.PANEL }
    private val pill = RectF()
    private val padX = context.dp(Tokens.S5)
    private val padY = context.dp(10f)
    private var text: CharSequence = ""
    private var baseline = 0f

    private val hide = Runnable {
        animate().alpha(0f).setDuration(if (HomeMotion.enabled) FADE_MS else 0).setInterpolator(Tokens.EASE_OUT)
            .withEndAction { visibility = INVISIBLE }
    }

    init {
        alpha = 0f
        visibility = INVISIBLE
        isFocusable = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun show(message: CharSequence) {
        removeCallbacks(hide)
        text = message
        contentDescription = message
        requestLayout()
        invalidate()
        visibility = VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(if (HomeMotion.enabled) FADE_MS else 0).setInterpolator(Tokens.EASE_OUT)
        postDelayed(hide, SHOW_MS)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val most = MeasureSpec.getSize(widthMeasureSpec)
        val lineHeight = normalLineHeight(textPaint)
        val w = min(most.toFloat(), textPaint.measureText(text, 0, text.length) + 2 * padX)
        setMeasuredDimension(ceil(w).toInt(), ceil(lineHeight + 2 * padY).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pill.set(0f, 0f, w.toFloat(), h.toFloat())
        baseline = baselineIn(textPaint, padY, h - 2 * padY)
        text = TextUtils.ellipsize(text, textPaint, w - 2 * padX, TextUtils.TruncateAt.END)
    }

    override fun onDraw(canvas: Canvas) {
        val r = pill.height() / 2f
        canvas.drawRoundRect(pill, r, r, pillPaint)
        canvas.drawText(text, 0, text.length, padX, baseline, textPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        super.onDetachedFromWindow()
    }

    companion object {
        const val FADE_MS = 180L
        const val SHOW_MS = 1600L
    }
}
