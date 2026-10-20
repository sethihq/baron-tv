package com.barontv.home.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.ViewGroup
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import com.barontv.home.core.dpi
import kotlin.math.roundToInt

/**
 * A poster on the shelf: the art (radius 16), its title (15, brightening with focus) and the details under
 * it (13, fully opaque with focus). With no art yet the art's place is a dark fill with a film slate,
 * lighter while focused.
 */
class PosterView(context: Context) : ArtView(context) {
    private val titlePaint = context.textPaint(Fonts.medium(context), Tokens.T_BODY, Tokens.TEXT_2, -0.006f)
    private val metaPaint = context.textPaint(Fonts.medium(context), Tokens.T_LABEL, Tokens.TEXT_2)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphSize = context.dpi(36f)
    private val glyph: Bitmap = Glyphs.mask(context, R.drawable.ph_film_slate, glyphSize)

    private var title = ""
    private var meta = ""
    private var shownTitle: CharSequence = ""
    private var shownMeta: CharSequence = ""
    private var titleBaseline = 0f
    private var metaBaseline = 0f

    /** The art's height (px); the shelf sets it before measuring, so all three share one. */
    var artHeight = 0f

    fun setText(title: String, meta: String) {
        this.title = title
        this.meta = meta
        contentDescription = listOf(title, meta).filter { it.isNotEmpty() }.joinToString(", ")
        fitText()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val c = context
        placeArt(ringSpace, ringSpace, w - 2 * ringSpace, artHeight, c.dp(16f))
        val titleTop = art.bottom + c.dp(Tokens.S3)
        titleBaseline = baselineIn(titlePaint, titleTop, c.dp(Tokens.T_BODY * 1.3f))
        metaBaseline = baselineIn(metaPaint, titleTop + c.dp(Tokens.T_BODY * 1.3f) + c.dp(2f), c.dp(Tokens.T_LABEL * 1.3f))
        fitText()
    }

    private fun fitText() {
        val width = art.width()
        if (width <= 0) return
        shownTitle = TextUtils.ellipsize(title, titlePaint, width, TextUtils.TruncateAt.END)
        shownMeta = TextUtils.ellipsize(meta, metaPaint, width, TextUtils.TruncateAt.END)
    }

    override fun drawEmptyArt(canvas: Canvas, focused: Float) {
        fillPaint.color = mixColor(Tokens.FILL, Tokens.POSTER_FOCUS, focused)
        canvas.drawRoundRect(art, radius, radius, fillPaint)
        // The film slate at 60%, its colour going from the secondary text grey to the text white.
        glyphPaint.color = mixColor(Tokens.TEXT_2, Tokens.TEXT, focused)
        glyphPaint.alpha = (255 * 0.6f).roundToInt()
        canvas.drawBitmap(glyph, art.centerX() - glyphSize / 2f, art.centerY() - glyphSize / 2f, glyphPaint)
    }

    override fun drawUnderArt(canvas: Canvas, focused: Float) {
        titlePaint.color = mixColor(Tokens.TEXT_2, Tokens.TEXT, focused)
        canvas.drawText(shownTitle, 0, shownTitle.length, art.left, titleBaseline, titlePaint)
        if (shownMeta.isNotEmpty()) {
            metaPaint.color = Tokens.TEXT_2
            metaPaint.alpha = (255 * (0.75f + 0.25f * focused)).roundToInt()
            canvas.drawText(shownMeta, 0, shownMeta.length, art.left, metaBaseline, metaPaint)
        }
    }

    companion object {
        /** Below the art: 12 to the title's 19.5 line, 2 to the details' 16.9 line. */
        fun textHeight(context: Context): Float =
            context.dp(Tokens.S3 + Tokens.T_BODY * 1.3f + 2f + Tokens.T_LABEL * 1.3f)
    }
}

/**
 * The shelf: its head ("New on your apps" on the left, the data's credit on the right, sharing a
 * baseline) over three posters, one wide and two 124 wide, 16 apart, their art one height so the titles
 * share a line. It fills the shelf slot and reaches 3 dp past it on three sides, so the posters' focus
 * rings stay inside it.
 */
class ShelfView(context: Context) : ViewGroup(context) {
    val posters = List(3) { PosterView(context) }

    private val headPaint = context.textPaint(Fonts.semibold(context), Tokens.T_LABEL, Tokens.TEXT_2)
    private val creditPaint = context.textPaint(Fonts.medium(context), Tokens.T_CAPTION, Tokens.TEXT_2, 0.012f).apply {
        alpha = (255 * 0.7f).roundToInt()
    }
    private var head = ""
    private var credit = ""
    private var creditWidth = 0f
    private var headBaseline = 0f

    /** The ring's room round the posters: the shelf's own edge sits this far outside the slot. */
    val overhang: Int = posters[0].ringSpace.roundToInt()

    init {
        for (p in posters) addView(p)
        setWillNotDraw(false)
        clipChildren = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setHead(head: String, credit: String) {
        this.head = head
        this.credit = credit
        creditWidth = creditPaint.measureText(credit)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val g = geometry(context, measuredWidth - 2f * overhang, measuredHeight - overhang.toFloat())
        for ((i, p) in posters.withIndex()) {
            p.artHeight = g.artHeight
            p.measure(
                MeasureSpec.makeMeasureSpec((g.widths[i] + 2 * overhang).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((overhang + g.artHeight + PosterView.textHeight(context)).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = geometry(context, width - 2f * overhang, height - overhang.toFloat())
        for ((i, p) in posters.withIndex()) {
            // A poster's bounds start a ring's width before its art, which sits at the slot's own x.
            val x = g.lefts[i].roundToInt()
            val y = g.artTop.roundToInt()
            p.layout(x, y, x + p.measuredWidth, y + p.measuredHeight)
        }
        headBaseline = baselineIn(headPaint, overhang.toFloat(), context.dp(Tokens.T_LABEL * 1.3f))
    }

    class Geometry(val lefts: FloatArray, val widths: FloatArray, val artTop: Float, val artHeight: Float)

    companion object {
        /**
         * The prototype's grid in a slot [slotWidth] x [slotHeight] px (slot coordinates): the head's
         * 16.9 line and 12 under it, then columns 1fr 124 124 with gaps of 16, the art taking whatever
         * height the titles leave.
         */
        fun geometry(c: Context, slotWidth: Float, slotHeight: Float): Geometry {
            val narrow = c.dp(124f)
            val gap = c.dp(Tokens.S4)
            val wide = slotWidth - 2 * narrow - 2 * gap
            val artTop = c.dp(Tokens.T_LABEL * 1.3f + Tokens.S3)
            val artHeight = slotHeight - artTop - PosterView.textHeight(c)
            return Geometry(
                lefts = floatArrayOf(0f, wide + gap, wide + gap + narrow + gap),
                widths = floatArrayOf(wide, narrow, narrow),
                artTop = artTop,
                artHeight = artHeight,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val left = overhang.toFloat()
        canvas.drawText(head, left, headBaseline, headPaint)
        if (credit.isNotEmpty()) canvas.drawText(credit, width - overhang - creditWidth, headBaseline, creditPaint)
    }
}
