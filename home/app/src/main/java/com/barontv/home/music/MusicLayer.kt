package com.barontv.home.music

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Music mode on screen, over the whole home screen: the black dim, the edge light and the beat bot.
 * Coming in, the light is over the dim; going out it is under it (in the prototype it dropped behind the
 * UI), so it fades out with the black. Only what can be seen is drawn: a part at nothing is invisible,
 * and while the home screen behind is hidden the dim isn't drawn at all (the screen is black anyway).
 */
@SuppressLint("ViewConstructor")
internal class MusicLayer(context: Context, private val engine: MusicEngine) : FrameLayout(context) {
    private val dim = Dim(context)
    val light = LightView(context, engine.light)
    val face = BotView(context, engine.bot)
    private var lightUnderDim = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isChildrenDrawingOrderEnabled = true
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The light's own pixels: the prototype's half-resolution canvas, scaled up in onSizeChanged.
        addView(light, LayoutParams(EdgeLight.W.toInt(), EdgeLight.H.toInt()))
        light.pivotX = 0f
        light.pivotY = 0f
        addView(face, LayoutParams(0, 0, Gravity.CENTER))
    }

    // The bot is 360 x 240 of the prototype's 960 x 540, so on the TV exactly 480 x 320 pixels.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lp = face.layoutParams
        lp.width = (MeasureSpec.getSize(widthMeasureSpec) * BeatBot.W / SCREEN_W).roundToInt()
        lp.height = (MeasureSpec.getSize(heightMeasureSpec) * BeatBot.H / SCREEN_H).roundToInt()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        light.scaleX = w / EdgeLight.W.toFloat()
        light.scaleY = h / EdgeLight.H.toFloat()
    }

    /** Shows the engine's frame: each part's opacity, and the light and bot redrawn if they moved. */
    fun render(behindHidden: Boolean) {
        val d = engine.dim.value
        dim.alpha = d
        dim.visibility = if (d > 0f && !behindHidden) VISIBLE else INVISIBLE
        val g = engine.glow.value
        light.alpha = g
        light.visibility = if (g > 0f) VISIBLE else INVISIBLE
        if (g > 0f) light.invalidate()
        val f = engine.face.value
        face.alpha = f
        face.visibility = if (f > 0f) VISIBLE else INVISIBLE
        if (f > 0f && engine.bot.showing) face.invalidate()
        if (engine.lightUnderDim != lightUnderDim) {
            lightUnderDim = engine.lightUnderDim
            invalidate()
        }
    }

    // Children are dim (0), light (1), bot (2); leaving, the light goes under the dim.
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int = when {
        !lightUnderDim || drawingPosition > 1 -> drawingPosition
        else -> 1 - drawingPosition
    }

    fun release() {
        light.release()
    }

    private companion object {
        const val SCREEN_W = 960.0
        const val SCREEN_H = 540.0
    }

    // The black: a plain fill, faded by its alpha with no offscreen layer.
    private class Dim(context: Context) : View(context) {
        init {
            setBackgroundColor(Color.BLACK)
        }

        override fun hasOverlappingRendering() = false
    }
}
