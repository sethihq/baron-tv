package com.barontv.home.side

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.roundToInt

/** The volume's sums, free of Android types so they can be tested on the JVM. */
internal object Volume {
    /** Up and Down move the music stream by 5% of its range, at least one step. */
    fun step(max: Int): Int = maxOf(1, (max * 0.05f).roundToInt())

    fun next(index: Int, max: Int, up: Boolean): Int = (index + if (up) step(max) else -step(max)).coerceIn(0, max)

    /** The number the bar shows, 0 to 100. */
    fun percent(index: Int, max: Int): Int = if (max <= 0) 0 else (index * 100f / max).roundToInt().coerceIn(0, 100)

    /** The stream index for a pointer at [percent] of the bar, kept to the 5% steps. */
    fun indexFor(percent: Float, max: Int): Int {
        val stepped = (percent / 5f).roundToInt() * 5
        return (stepped * max / 100f).roundToInt().coerceIn(0, max)
    }

    /** sidebar.css's glyphs: crossed out at 0, low under half, high from half up. */
    val GLYPHS = intArrayOf(R.drawable.ph_speaker_simple_x, R.drawable.ph_speaker_simple_low, R.drawable.ph_speaker_simple_high)

    /** Which of [GLYPHS] a level shows. */
    fun glyphIndex(percent: Int): Int = when {
        percent <= 0 -> 0
        percent < 50 -> 1
        else -> 2
    }

    fun glyph(percent: Int): Int = GLYPHS[glyphIndex(percent)]
}

/**
 * Sound (sidebar.css's .sv-level): the whole box turned into one tall volume bar. The level fills it from
 * the bottom in white; the glyph sits at the top and "Volume" with the big number at the bottom, drawn
 * twice, light on the dark bar and dark on the fill (clipped to the level), so they read on either even
 * split across the fill's edge. Up and Down move the TV's music volume in fives; volume changes made with
 * the remote while it's open move it too.
 */
internal class SoundBar(context: Context) : View(context) {

    /** The dock's background thread, for the calls into AudioManager; set by the dock before use. */
    var worker: SideWorker? = null

    private fun dp(v: Float) = context.dp(v)

    private val inset = dp(5f)
    private val radius = dp(14f)
    private val padIn = dp(12f)
    private val glyphPx = dp(22f).roundToInt()

    private val bar = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SideColors.LEVEL_FILL }
    private val caption = Type.paint(context, Fonts.semibold(context), 12f, Tokens.TEXT_2, 0.01f)
    private val captionOnFill = Type.paint(context, Fonts.semibold(context), 12f, SideColors.ON_FILL_CAPTION, 0.01f)
    private val number = Type.paint(context, Fonts.semibold(context), 34f, Tokens.TEXT, -0.035f).apply {
        fontFeatureSettings = "'tnum'"
    }
    private val numberOnFill = Type.paint(context, Fonts.semibold(context), 34f, SideColors.ON_FILL, -0.035f).apply {
        fontFeatureSettings = "'tnum'"
    }
    private val volumeText = context.getString(R.string.side_volume)

    // The three glyphs, light and dark, made once.
    private val light: Array<Drawable> = Array(3) { context.glyph(Volume.GLYPHS[it], Tokens.TEXT) }
    private val dark: Array<Drawable> = Array(3) { context.glyph(Volume.GLYPHS[it], SideColors.ON_FILL) }

    private var max = 100
    private var index = 35
    private var muted = false
    private var shownPercent = 35
    private var label = "35"
    private var fillFrac = 0.35f
    private var fillFrom = 0.35f
    private var fillTo = 0.35f
    private val fillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 180
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            fillFrac = fillFrom + (fillTo - fillFrom) * it.animatedFraction
            invalidate()
        }
    }

    private var focusMix = 0f
    private var mixFrom = 0f
    private var mixTo = 0f
    private val focusFade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            focusMix = mixFrom + (mixTo - mixFrom) * it.animatedFraction
            invalidate()
        }
    }

    // From the bottom: 12 dp padding, the number's 35.7 dp line (34 at 1.05), "Volume" right above it.
    private val numberRise = dp(17.7f)
    private val captionRise = dp(50.7f)

    // Fetched on first use: the snapshot tests' stand-in Android has no audio service.
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    private var listening = false
    private var lastLocalChange = Long.MIN_VALUE / 2

    // The first reading after opening lands at once; later ones (the remote's volume keys) slide.
    private var known = false

    // The remote's volume keys (and anything else) while the bar is open; the extra names are
    // AudioManager's hidden VOLUME_CHANGED_ACTION and STREAM_MUTE_CHANGED_ACTION, sent to every receiver.
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
            if (stream == AudioManager.STREAM_MUSIC) read()
        }
    }

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        isClickable = true
        contentDescription = volumeText
        setLevel(index, max, muted = false, animate = false)
    }

    /** The bar opens: read the volume and follow it. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // system broadcasts only, on API 28
    fun start() {
        if (listening || audio == null) return
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED)
            addAction(MUTE_CHANGED)
        }
        val w = worker ?: return
        listening = true
        context.registerReceiver(changes, filter, null, w.handler())
        read()
    }

    /** The bar closes: stop following. */
    fun stop() {
        if (!listening) return
        listening = false
        known = false
        try {
            context.unregisterReceiver(changes)
        } catch (e: IllegalArgumentException) {
            // Never registered: nothing to undo.
        }
    }

    /** Reads the stream on the worker; a key press in the last moment wins over the echo of an older one. */
    private fun read() {
        val am = audio ?: return
        val w = worker ?: return
        w.post {
            val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val i = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val mute = am.isStreamMute(AudioManager.STREAM_MUSIC)
            w.toMain {
                if (SystemClock.uptimeMillis() - lastLocalChange > ECHO_MS) setLevel(i, m, mute, animate = known && isShown)
                known = true
            }
        }
    }

    /** Shows a level: [index] of [max] on the music stream, or muted. */
    fun setLevel(index: Int, max: Int, muted: Boolean, animate: Boolean) {
        this.max = max
        this.index = index
        this.muted = muted
        shownPercent = if (muted) 0 else Volume.percent(index, max)
        label = shownPercent.toString()
        contentDescription = "$volumeText $label"
        fillFrom = fillFrac
        fillTo = shownPercent / 100f
        fillAnim.cancel()
        if (animate) fillAnim.start() else fillFrac = fillTo
        invalidate()
    }

    private fun change(newIndex: Int) {
        if (newIndex == index && !muted) return
        lastLocalChange = SystemClock.uptimeMillis()
        setLevel(newIndex, max, muted = false, animate = true)
        val am = audio ?: return
        // No flags: the TV's own volume overlay stays away, the bar is the feedback.
        worker?.post { am.setStreamVolume(AudioManager.STREAM_MUSIC, newIndex, 0) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val from = if (muted) 0 else index
            change(Volume.next(from, max, keyCode == KeyEvent.KEYCODE_DPAD_UP))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // The remote's pointer: a click or drag sets the level where it is, in fives.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!isFocused) requestFocus()
                val p = (bar.bottom - event.y) / bar.height() * 100f
                change(Volume.indexFor(p.coerceIn(0f, 100f), max))
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        mixFrom = focusMix
        mixTo = if (gainFocus) 1f else 0f
        focusFade.cancel()
        if (isAttachedToWindow) focusFade.start() else focusMix = mixTo
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bar.set(inset, inset, w - inset, h - inset)
        val l = (bar.left + padIn).roundToInt()
        val t = (bar.top + padIn).roundToInt()
        for (d in light) d.setBounds(l, t, l + glyphPx, t + glyphPx)
        for (d in dark) d.setBounds(l, t, l + glyphPx, t + glyphPx)
    }

    override fun onDraw(canvas: Canvas) {
        bg.color = SideColors.mix(Tokens.TILE, SideColors.LEVEL_FOCUS, focusMix)
        canvas.drawRoundRect(bar, radius, radius, bg)
        val g = Volume.glyphIndex(shownPercent)
        drawContent(canvas, light[g], caption, number)
        // The level, and the same content in dark where it covers it.
        val top = bar.bottom - bar.height() * fillFrac
        if (fillFrac > 0f) {
            canvas.save()
            canvas.clipRect(bar.left, top, bar.right, bar.bottom)
            canvas.drawRoundRect(bar, radius, radius, fillPaint)
            drawContent(canvas, dark[g], captionOnFill, numberOnFill)
            canvas.restore()
        }
    }

    private fun drawContent(canvas: Canvas, glyph: Drawable, captionPaint: Paint, numberPaint: Paint) {
        glyph.draw(canvas)
        val x = bar.left + padIn
        canvas.drawText(label, x, bar.bottom - numberRise, numberPaint)
        canvas.drawText(volumeText, x, bar.bottom - captionRise, captionPaint)
    }

    private companion object {
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

        /** A key press's own echo arrives within this; older news is ignored meanwhile. */
        const val ECHO_MS = 400L
    }
}
