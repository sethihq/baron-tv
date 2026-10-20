package com.barontv.home.music

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * Paints the beat bot's [BotPose] in flat white, as the prototype's canvas did: rounded eyes, arches
 * when it's happy, hearts, a smile, its laugh and its gasp. The view is the prototype's 360 x 240 canvas,
 * centred; one Path and one Paint are reused every frame.
 */
@SuppressLint("ViewConstructor")
internal class BotView(context: Context, private val bot: BeatBot) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    // Its eyes and mouth barely overlap, so a fade needs no offscreen layer.
    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val p = bot.pose
        val s = width / BeatBot.W.toFloat()
        canvas.save()
        canvas.scale(s, s)
        canvas.translate(p.cx, p.cy)
        canvas.rotate(p.tilt)
        for (e in p.eyes) {
            when (e.kind) {
                EyePose.ROUND -> {
                    fill(1f)
                    box.set(e.x - e.w / 2, e.y - e.h / 2, e.x + e.w / 2, e.y + e.h / 2)
                    canvas.drawRoundRect(box, e.corner, e.corner, paint)
                }
                EyePose.ARCH -> {
                    stroke(1f, e.stroke)
                    path.rewind()
                    path.moveTo(e.x - e.reach, e.y + 10 * e.lift)
                    path.quadTo(e.x, e.y - 30 * e.lift, e.x + e.reach, e.y + 10 * e.lift)
                    canvas.drawPath(path, paint)
                }
                EyePose.HEART -> {
                    fill(1f)
                    heart(e.x, e.y, e.size)
                    canvas.drawPath(path, paint)
                }
            }
        }
        if (p.mouthLine > 0f || p.mouthFill > 0f) {
            path.rewind()
            path.moveTo(p.mouthX - p.mouthW, p.mouthY)
            path.quadTo(p.mouthX, p.mouthY + p.mouthD, p.mouthX + p.mouthW, p.mouthY)
            if (p.mouthFill > 0f) {
                fill(p.mouthFill)
                canvas.drawPath(path, paint)
            }
            if (p.mouthLine > 0f) {
                stroke(p.mouthLine, MOUTH)
                canvas.drawPath(path, paint)
            }
        }
        if (p.oh > 0f) {
            stroke(p.oh, MOUTH)
            box.set(p.mouthX - 5.5f, p.mouthY + 4 - 7f, p.mouthX + 5.5f, p.mouthY + 4 + 7f)
            canvas.drawOval(box, paint)
        }
        canvas.restore()
    }

    private fun fill(alpha: Float) {
        paint.style = Paint.Style.FILL
        paint.color = WHITE
        paint.alpha = (alpha * 255f).roundToInt()
    }

    private fun stroke(alpha: Float, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = WHITE
        paint.alpha = (alpha * 255f).roundToInt()
    }

    private fun heart(cx: Float, cy: Float, s: Float) {
        path.rewind()
        path.moveTo(cx, cy + 0.42f * s)
        path.cubicTo(cx - 0.62f * s, cy + 0.02f * s, cx - 0.56f * s, cy - 0.56f * s, cx, cy - 0.24f * s)
        path.cubicTo(cx + 0.56f * s, cy - 0.56f * s, cx + 0.62f * s, cy + 0.02f * s, cx, cy + 0.42f * s)
        path.close()
    }

    companion object {
        /** oklch(96% 0 none): flat white, a touch under full so it isn't harsh on black. */
        val WHITE = Colour.oklchToRgb(0.96, 0.0, 0.0)
        private const val MOUTH = 3.5f
    }
}
