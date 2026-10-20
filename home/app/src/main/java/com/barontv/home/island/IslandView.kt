package com.barontv.home.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.NowPlayingHub
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.roundToInt

/**
 * The now-playing island at the top centre: a black pill while something plays, opening into controls
 * when focused. It fills the island slot (the whole screen) and places itself: centred in the gap between
 * the bento and the side dock, level with their tops (the prototype's sizeIsland), which is x 480, y 32.
 *
 * Hidden while nothing plays. Showing, it joins the top row: Up from the shelf reaches it, focus lands on
 * play/pause, Left and Right walk the controls, Down leaves. As in the prototype it also opens for a
 * moment (4.5 s) when the song changes or music starts, without taking focus.
 */
@SuppressLint("ViewConstructor") // made in code by HomeActivity, which hands it the host
class IslandView(context: Context, private val host: HomeHost) : FrameLayout(context) {

    internal val pill = IslandPill(context)
    private var current: NowPlaying? = null
    private var peeking = false
    private val endPeek = Runnable {
        peeking = false
        syncOpen(true)
    }
    private val startPeek = Runnable { peek() }
    private val islEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private var appear: ValueAnimator? = null
    private val at = IntArray(2)
    private val atBento = IntArray(2)
    private val atDock = IntArray(2)

    private val listener: (NowPlaying?) -> Unit = { np -> onNowPlaying(np) }

    /** Off in the snapshot tests, which want the island exactly as set up. */
    internal var peeks = true

    init {
        clipChildren = false
        pill.visibility = GONE
        pill.onFocus = { syncOpen(true) }
        addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NowPlayingHub.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        NowPlayingHub.removeListener(listener)
        removeCallbacks(endPeek)
        removeCallbacks(startPeek)
        appear?.cancel()
        super.onDetachedFromWindow()
    }

    /** What NowPlayingHub says now; also how the snapshot tests show a fixture song. */
    internal fun onNowPlaying(np: NowPlaying?) {
        val was = current
        current = np
        if (np == null) {
            hide()
            return
        }
        val appearing = pill.visibility != VISIBLE
        pill.show(np, animate = !appearing)
        if (appearing) reveal()
        // A new song, or music starting, opens the island for a moment, as the phone does.
        if (!peeks) return
        val newSong = was != null && (np.title != was.title || np.artist != was.artist || np.packageName != was.packageName)
        if (newSong) peek() else if (was == null && np.playing) {
            removeCallbacks(startPeek)
            postDelayed(startPeek, 350)
        }
    }

    private fun reveal() {
        pill.visibility = VISIBLE
        pill.onShownChanged()
        appear?.cancel()
        // island.css's @starting-style: from 0.6 and clear, about the compact pill's middle.
        pill.pivotX = context.dp(364f) / 2f
        pill.pivotY = context.dp(17f)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 420
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val ms = it.animatedFraction * 420f
            pill.alpha = Tokens.EASE_OUT.getInterpolation((ms / 320f).coerceAtMost(1f))
            val s = 0.6f + 0.4f * islEase.getInterpolation(it.animatedFraction)
            pill.scaleX = s
            pill.scaleY = s
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                pill.alpha = 1f
                pill.scaleX = 1f
                pill.scaleY = 1f
            }
        })
        appear = a
        if (isShown) a.start() else a.end()
    }

    private fun hide() {
        removeCallbacks(endPeek)
        removeCallbacks(startPeek)
        peeking = false
        appear?.cancel()
        if (pill.visibility != VISIBLE) return
        // Focus moves down first, so it doesn't land somewhere arbitrary when the island goes.
        if (pill.hasFocus()) pill.focusSearch(FOCUS_DOWN)?.requestFocus(FOCUS_DOWN)
        pill.setOpen(false, animate = false)
        pill.visibility = GONE
        pill.onShownChanged()
    }

    private fun peek() {
        if (pill.visibility != VISIBLE || pill.isFocused || !isShown) return
        peeking = true
        syncOpen(true)
        removeCallbacks(endPeek)
        postDelayed(endPeek, PEEK_MS)
    }

    /** Open while focused or peeking. */
    private fun syncOpen(animate: Boolean) {
        if (pill.isFocused) {
            peeking = false
            removeCallbacks(endPeek)
        }
        pill.setOpen(pill.isFocused || peeking, animate)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Coming back to the home screen with the island peeking: just close it.
        if (visibility != VISIBLE && peeking) {
            removeCallbacks(endPeek)
            peeking = false
            syncOpen(false)
        }
    }

    /** For snapshots: open or close at once, as if focused (true) or after focus left. */
    internal fun setOpenForTest(open: Boolean) {
        pill.setOpen(open, animate = false)
        if (open) pill.requestFocus()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Centred in the gap between the bento (left) and the side dock (right), level with their tops.
        val root = rootView
        val bento = root.findViewById<View>(R.id.bentoSlot)
        val dock = root.findViewById<View>(R.id.sideDockSlot)
        var cx = context.dp(480f)
        var y = context.dp(32f)
        if (bento != null && dock != null && bento.width > 0 && dock.width > 0) {
            bento.getLocationInWindow(atBento)
            dock.getLocationInWindow(atDock)
            getLocationInWindow(at)
            cx = ((atBento[0] + bento.width + atDock[0]) / 2f) - at[0]
            y = (atDock[1] - at[1]).toFloat()
        }
        val w = pill.measuredWidth
        val x = (cx - w / 2f).roundToInt()
        pill.layout(x, y.roundToInt(), x + w, y.roundToInt() + pill.measuredHeight)
    }

    private companion object {
        const val PEEK_MS = 4500L
    }
}
