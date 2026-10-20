package com.barontv.home.saver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.barontv.home.core.Fonts
import kotlin.math.tan

/**
 * The screensaver's screen: the logo on its own small hardware layer, moved by its translation each
 * frame, so a frame is one textured square in a new place (the logo redraws only when its colour
 * changes, and fades by the layer's alpha). The black is the dream window's own background. Everything is
 * laid out on the prototype's 960 x 540 screen and scaled to the real one, whatever the dream window's
 * density.
 */
@SuppressLint("ViewConstructor")
internal class DvdView(context: Context, val motion: DvdMotion) : ViewGroup(context) {
    val logo = Logo(context)
    private var scale = 1f

    init {
        addView(logo)
        if (!isInEditMode) logo.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        scale = w / DvdMotion.SCREEN_W.toFloat()
        logo.measure(
            MeasureSpec.makeMeasureSpec((DvdMotion.LOGO_W * scale).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((DvdMotion.LOGO_H * scale).toInt(), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        logo.layout(0, 0, logo.measuredWidth, logo.measuredHeight)
        place(0.0)
    }

    /** The logo where the motion has it at [now]: position, colour and the corner pulse. */
    fun place(now: Double) {
        logo.translationX = (motion.x * scale).toFloat()
        logo.translationY = (motion.y * scale).toFloat()
        val s = motion.scale(now)
        logo.scaleX = s
        logo.scaleY = s
        logo.colour = DvdMotion.COLOURS[motion.colour]
    }

    /**
     * The old DVD logo, our own drawing (not an official file), as the prototype's SVG: "DVD" in Open Runde
     * Bold, slanted 12 degrees, over a flat disc with "VIDEO" cut out of it in black. Drawn in the SVG's
     * 200 x 104 units.
     */
    internal class Logo(context: Context) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dvd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.bold(context)
            textSize = 66f
            textAlign = Paint.Align.CENTER
            letterSpacing = -2f / 66f
            // The SVG's skewX(-12), as text skew: the renderer's own faux italic.
            textSkewX = SKEW
        }
        private val video = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.bold(context)
            textSize = 15f
            textAlign = Paint.Align.CENTER
            letterSpacing = 6f / 15f
            color = Color.BLACK
        }
        private val disc = RectF(7f, 69f, 193f, 99f)

        var colour = 0
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w / 2f
            pivotY = h / 2f
        }

        override fun onDraw(canvas: Canvas) {
            val s = width / 200f
            canvas.save()
            canvas.scale(s, s)
            ink.color = colour
            dvd.color = colour
            // The SVG sets the word at x 111 before its skew, which moves the baseline 60 * tan(12) left.
            // SVG's letter-spacing also follows the last letter, and text-anchor counts it: half of it
            // shifts the word, which Android's centred spacing doesn't.
            canvas.drawText("DVD", 111f + SKEW * 60f + 1f, 60f, dvd)
            canvas.drawOval(disc, ink)
            canvas.drawText("VIDEO", 100f - 3f, 89.6f, video)
            canvas.restore()
        }
    }

    companion object {
        private val SKEW = tan(Math.toRadians(-12.0)).toFloat()
    }
}
