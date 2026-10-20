package com.barontv.home.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.roundToInt

/**
 * An app in the dock: its cover (radius 14, cropped to fill the tile) with a white 10% hairline just inside
 * the edge, so black covers keep their shape on the dark tray. Focus is the art's fine ring, nothing more.
 * Made in code for its app, never inflated, so it has no layout-tool constructors.
 */
@SuppressLint("ViewConstructor")
class AppTileView(context: Context, val app: DockApp) : ArtView(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.FILL }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = 0x1AFFFFFF
    }
    private val hair = RectF()

    init {
        contentDescription = app.label
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeArt(ringSpace, ringSpace, w - 2 * ringSpace, h - 2 * ringSpace, context.dp(14f))
        val half = context.dp(0.5f)
        hair.set(art)
        hair.inset(half, half)
    }

    override fun drawEmptyArt(canvas: Canvas, focused: Float) {
        canvas.drawRoundRect(art, radius, radius, fillPaint)
    }

    override fun drawOverArt(canvas: Canvas) {
        val r = radius - context.dp(0.5f)
        canvas.drawRoundRect(hair, r, r, hairPaint)
    }
}

/**
 * The app dock (app.css .dock): a tray of white at 7.5% (so the glow tints it), radius 26, 12 of padding,
 * the tiles in equal columns 12 apart filling it, as the prototype lays out its seven. The tiles keep the
 * seven-column height, so with fewer apps they widen and their covers crop a little top and bottom, and the
 * tray never moves; with more than seven the row scrolls sideways to keep the focused tile in view.
 *
 * Left and Right walk the row and stop at its ends (they never jump up to the side dock); Down goes nowhere.
 */
class DockView(context: Context) : ViewGroup(context) {
    var tiles: List<AppTileView> = emptyList()
        private set

    private val pad = context.dp(Tokens.S3)
    private val gap = context.dp(Tokens.S3)
    private val trayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.BAND }
    private val tray = RectF()
    private val trayRadius = context.dp(26f)
    private val scroll = Tween(0f)

    /** What OK on a tile does: open its app. */
    var onOpen: ((DockApp) -> Unit)? = null

    init {
        setWillNotDraw(false)
        clipChildren = true
        // Each tile reports its own focus; the tray itself never takes it.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    /** Shows [apps] in order, keeping focus on the same app if it's still here. */
    fun setApps(apps: List<DockApp>) {
        if (apps == tiles.map { it.app }) return
        val focusedApp = tiles.firstOrNull { it.isFocused }?.app?.packageName
        val old = tiles.associateBy { it.app.packageName }
        removeAllViews()
        tiles = apps.map { app -> old[app.packageName]?.takeIf { it.app == app } ?: AppTileView(context, app) }
        for (t in tiles) {
            t.onPress = { onOpen?.invoke(t.app) }
            addView(t)
        }
        scroll.snap(0f)
        scrollTo(0, 0)
        if (focusedApp != null) tiles.firstOrNull { it.app.packageName == focusedApp }?.requestFocus()
    }

    fun tileFor(packageName: String): AppTileView? = tiles.firstOrNull { it.app.packageName == packageName }

    /** A tile's width (px) in a tray [trayWidth] wide holding [count] apps: one column per app up to the
     *  prototype's seven, and never fewer than five columns, so a couple of apps don't stretch. */
    private fun tileWidth(trayWidth: Int, count: Int): Float {
        val cols = count.coerceIn(5, 7)
        return (trayWidth - 2 * pad - (cols - 1) * gap) / cols
    }

    /** A tile's height (px): the seven-column tile's 16:9 height, whatever the count. */
    private fun tileHeight(trayWidth: Int): Float = (trayWidth - 2 * pad - 6 * gap) / 7f * 9f / 16f

    /** The size (px) a cover is decoded at for [count] apps in a tray [trayWidth] wide. */
    fun tileSize(trayWidth: Int, count: Int): Pair<Int, Int> =
        tileWidth(trayWidth, count).roundToInt() to tileHeight(trayWidth).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
        val ring = 2 * (tiles.firstOrNull()?.ringSpace ?: 0f)
        for (t in tiles) {
            t.measure(
                MeasureSpec.makeMeasureSpec((tileWidth(w, tiles.size) + ring).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((tileHeight(w) + ring).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val tileW = tileWidth(width, tiles.size)
        for ((i, tile) in tiles.withIndex()) {
            val x = (pad + i * (tileW + gap) - tile.ringSpace).roundToInt()
            val y = (pad - tile.ringSpace).roundToInt()
            tile.layout(x, y, x + tile.measuredWidth, y + tile.measuredHeight)
        }
        tray.set(0f, 0f, width.toFloat(), height.toFloat())
    }

    private val contentWidth: Float
        get() = 2 * pad + tiles.size * tileWidth(width, tiles.size) + (tiles.size - 1).coerceAtLeast(0) * gap

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        reveal(child)
    }

    /** Scrolls (240 ms) just enough to show [child] whole, when the row is wider than the tray. */
    private fun reveal(child: View) {
        val max = contentWidth - width
        if (max <= 0f) return
        val ring = (child as? ArtView)?.ringSpace ?: 0f
        val left = child.left + ring
        val right = child.right - ring
        var target = scroll.target
        if (left - pad < target) target = left - pad
        if (right + pad > target + width) target = right + pad - width
        scroll.to(target.coerceIn(0f, max), 240)
        invalidate()
    }

    override fun computeScroll() {
        val moving = scroll.step()
        val x = scroll.value.roundToInt()
        if (x != scrollX) scrollTo(x, 0)
        if (moving) postInvalidateOnAnimation()
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val i = tiles.indexOf(focused)
        if (i >= 0) {
            when (direction) {
                FOCUS_LEFT -> return tiles.getOrNull(i - 1) ?: focused
                FOCUS_RIGHT -> return tiles.getOrNull(i + 1) ?: focused
                FOCUS_DOWN -> return focused
            }
        }
        return super.focusSearch(focused, direction)
    }

    override fun onDraw(canvas: Canvas) {
        // The tray stays put while the row scrolls under it.
        val save = canvas.save()
        canvas.translate(scrollX.toFloat(), 0f)
        canvas.drawRoundRect(tray, trayRadius, trayRadius, trayPaint)
        canvas.restoreToCount(save)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val ring = tiles.firstOrNull()?.ringSpace ?: 0f
        val save = canvas.save()
        canvas.clipRect(scrollX + pad - ring, 0f, scrollX + width - pad + ring, height.toFloat())
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
