package com.barontv.home.side

import android.content.Context
import android.graphics.Canvas
import android.view.ViewGroup
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.roundToInt

/**
 * The dock's home view (bento.css's .bento-dock): "Inputs" over the inputs side by side, then "TV" over
 * Wi-Fi, Picture, Sound and Settings, two to a row. The rows share the height the dock is given, the 2 x 2
 * taking two parts to the inputs' one, as the prototype's grid does. The labels are drawn here; the tiles
 * are views so the system's focus search moves between them and the rest of the screen.
 */
internal class SideHome(context: Context) : ViewGroup(context) {

    private fun dp(v: Float) = context.dp(v)

    private val pad = dp(5f)
    private val labelX = dp(5f + 9f)
    // A label's box: 12 dp above (11 for the second), its 13.2 dp line, 6 below; the baseline 11 dp into it.
    private val inputsLabelH = dp(12f + 13.2f + 6f)
    private val tvLabelH = dp(11f + 13.2f + 6f)
    private val inputsBaseline = dp(12f) + dp(Type.baseline(13.2f, 12f))
    private val tvBaseline = dp(11f) + dp(Type.baseline(13.2f, 12f))
    private val labelPaint = Type.paint(context, Fonts.semibold(context), 12f, Tokens.TEXT_2)

    private val inputsLabel = context.getString(R.string.side_inputs)
    private val tvLabel = context.getString(R.string.side_tv)
    private var tvTop = 0f

    val inputTiles = ArrayList<SideTile>(3)
    val tvTiles: List<SideTile> = List(4) { SideTile(context) }

    init {
        setWillNotDraw(false)
        clipChildren = false
        for (t in tvTiles) addView(t)
    }

    /** Makes sure there's a tile for each input, keeping the ones there are. */
    fun inputCount(n: Int) {
        while (inputTiles.size > n) removeView(inputTiles.removeAt(inputTiles.size - 1))
        while (inputTiles.size < n) SideTile(context).also {
            inputTiles.add(it)
            addView(it, inputTiles.size - 1)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        layoutTiles(w, h, measureOnly = true)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutTiles(r - l, b - t, measureOnly = false)
    }

    private fun layoutTiles(w: Int, h: Int, measureOnly: Boolean) {
        val inner = w - 2 * pad
        val rest = h - pad - inputsLabelH - tvLabelH
        val inputsH = rest / 3f
        val gridH = rest - inputsH
        val rowH = (gridH - pad) / 2f
        val n = inputTiles.size
        if (n > 0) {
            val tileW = (inner - (n - 1) * pad) / n
            for (i in 0 until n) {
                val x = pad + i * (tileW + pad)
                place(inputTiles[i], x, inputsLabelH, tileW, inputsH, measureOnly)
            }
        }
        tvTop = inputsLabelH + inputsH
        val colW = (inner - pad) / 2f
        for (i in tvTiles.indices) {
            val x = pad + (i % 2) * (colW + pad)
            val y = tvTop + tvLabelH + (i / 2) * (rowH + pad)
            place(tvTiles[i], x, y, colW, rowH, measureOnly)
        }
    }

    /** Edges rounded independently, so the 5 dp gaps stay even however the fractions fall. */
    private fun place(v: SideTile, x: Float, y: Float, w: Float, h: Float, measureOnly: Boolean) {
        val l = x.roundToInt()
        val t = y.roundToInt()
        val r = (x + w).roundToInt()
        val b = (y + h).roundToInt()
        if (measureOnly) {
            v.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY))
        } else {
            v.layout(l, t, r, b)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(inputsLabel, labelX, inputsBaseline, labelPaint)
        canvas.drawText(tvLabel, labelX, tvTop + tvBaseline, labelPaint)
    }
}
