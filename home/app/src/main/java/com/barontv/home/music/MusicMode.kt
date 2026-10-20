package com.barontv.home.music

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.barontv.home.R
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.NowPlayingHub
import kotlin.random.Random

/**
 * Music mode: while music plays and the remote rests (8 s), the screen dims to black and only the edge
 * light shows, round the whole screen at full strength, breathing with the song, with the beat bot
 * dancing in the middle. It owns the whole feature: the idle timer, the dimming, the light, the beat
 * detection and the bot, drawn in [layer].
 *
 * Only music: NowPlayingHub says whether what's playing is music and playing. Any key or touch ends it
 * (that first key only wakes the screen), as does the music stopping or the home screen leaving the
 * front. While it shows, the screen is kept on, so the TV's own screensaver (the DVD dream) never starts
 * over it. The frame loop runs only while it shows or fades, and the Visualizer only while it shows.
 */
class MusicMode(private val activity: Activity, private val layer: FrameLayout) {
    private val engine = MusicEngine(Random(System.nanoTime()))
    private val hearing = Hearing()
    private val ear = BeatEar(hearing)
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var view: MusicLayer? = null
    private var foreground = false
    private var music = false
    private var looping = false
    private var behindHidden = false
    private val behind = arrayOfNulls<View>(BEHIND.size)
    private val behindWas = IntArray(BEHIND.size)
    private var art: Bitmap? = null
    private val pixels = IntArray(COVER_PX * COVER_PX)

    private val enterLater = Runnable { enter() }
    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private val nowPlaying: (NowPlaying?) -> Unit = { onNowPlaying(it) }

    init {
        NowPlayingHub.addListener(nowPlaying)
    }

    val isOn: Boolean get() = engine.on

    /** Any key or touch: restarts the idle clock. Returns true if it woke music mode (swallow that key). */
    fun onUserActivity(): Boolean {
        if (engine.on) {
            exit()
            return true
        }
        arm()
        return false
    }

    /** The home screen is in front (onResume) or not (onPause): music mode only runs while it is. */
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (foreground) arm() else stopNow()
    }

    /** The activity is going away: release the visualizer and everything else. */
    fun release() {
        NowPlayingHub.removeListener(nowPlaying)
        stopNow()
        view?.let {
            layer.removeView(it)
            it.release()
        }
        view = null
    }

    private fun arm() {
        main.removeCallbacks(enterLater)
        if (foreground && music && !engine.on) main.postDelayed(enterLater, IDLE_MS)
    }

    private fun onNowPlaying(np: NowPlaying?) {
        val cover = np?.art
        if (cover !== art) {
            art = cover
            if (cover != null) {
                val rgb = coverColour(cover)
                if (rgb != 0) engine.light.setCover(rgb)
            }
        }
        val playing = np != null && np.isMusic && np.playing
        if (playing != music) {
            music = playing
            if (!playing) exit()
            arm()
        }
    }

    private fun enter() {
        if (engine.on || !foreground || !music) return
        val v = view ?: MusicLayer(activity, engine).also {
            view = it
            layer.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        v.visibility = View.VISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // With the TV's animations off: a still light and still eyes, nothing listening, no frame loop.
        engine.still = !ValueAnimator.areAnimatorsEnabled()
        val now = clock()
        engine.enter(now)
        if (engine.still) {
            engine.frame(now)
            hideBehind()
            v.render(behindHidden)
            return
        }
        engine.hearing = ear.start(activity)
        if (!engine.hearing && !loggedDeaf) {
            loggedDeaf = true
            Log.w(TAG, "Can't hear the TV (${ear.failure}); keeping a steady ${MusicEngine.TEMPO_BPM.toInt()} BPM")
        }
        loop()
    }

    private fun exit() {
        if (!engine.on) return
        engine.exit(clock())
        afterShow()
        if (engine.settled) finish() else loop()
        arm()
    }

    // The home screen left the front: everything off at once, nothing left running.
    private fun stopNow() {
        main.removeCallbacks(enterLater)
        engine.off()
        afterShow()
        finish()
    }

    private fun afterShow() {
        showBehind()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ear.stop()
        engine.hearing = false
    }

    private fun loop() {
        if (looping) return
        looping = true
        choreographer.postFrameCallback(frame)
    }

    private fun finish() {
        if (looping) choreographer.removeFrameCallback(frame)
        looping = false
        view?.visibility = View.GONE
    }

    private fun onFrame(frameTimeNanos: Long) {
        looping = false
        val v = view ?: return
        val now = frameTimeNanos / 1e6
        if (engine.on && ear.listening) {
            // As the prototype's animation frame did: hear, then draw.
            ear.hear(now, engine)
            // Digital silence for long while the song plays, or no samples at all: the capture isn't
            // getting the sound, so the tempo keeps the light and the bot going.
            val deaf = ear.failing || (hearing.silentSince >= 0 && now - hearing.silentSince > SILENT_MS)
            if (deaf && engine.hearing && !loggedSilent) {
                loggedSilent = true
                Log.w(TAG, "The TV's sound reads as silence; keeping a steady beat until it doesn't")
            }
            if (ear.unstitched && !loggedUnstitched) {
                loggedUnstitched = true
                Log.w(TAG, "The Visualizer's captures don't overlap; hearing one capture at a time")
            }
            engine.hearing = !deaf
        }
        engine.frame(now)
        if (engine.on && engine.dim.value >= 1f) hideBehind()
        v.render(behindHidden)
        if (engine.settled) {
            finish()
            return
        }
        loop()
    }

    // Under a fully black dim nothing behind can be seen, so it isn't drawn: the GPU draws only the light
    // and the bot.
    private fun hideBehind() {
        if (behindHidden) return
        behindHidden = true
        for (i in BEHIND.indices) {
            val v = activity.findViewById<View>(BEHIND[i]) ?: continue
            behind[i] = v
            behindWas[i] = v.visibility
            v.visibility = View.INVISIBLE
        }
    }

    private fun showBehind() {
        if (!behindHidden) return
        behindHidden = false
        for (i in BEHIND.indices) {
            val v = behind[i] ?: continue
            if (v.visibility == View.INVISIBLE) v.visibility = behindWas[i]
            behind[i] = null
        }
    }

    // The cover's most vivid colour, as the prototype picks it: the cover at 16 x 16, the first pixel with
    // the highest saturation-and-value score. 0 if the cover can't be read.
    private fun coverColour(cover: Bitmap): Int = try {
        val small = Bitmap.createScaledBitmap(cover, COVER_PX, COVER_PX, true)
        small.getPixels(pixels, 0, COVER_PX, 0, 0, COVER_PX, COVER_PX)
        if (small !== cover) small.recycle()
        var best = 0
        var bestScore = -1.0
        for (p in pixels) {
            val s = Colour.vividness(p)
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        best or (0xFF shl 24)
    } catch (e: RuntimeException) {
        0
    }

    companion object {
        private const val TAG = "BaronMusic"
        private const val IDLE_MS = 8000L
        private const val SILENT_MS = 6000.0
        private const val COVER_PX = 16
        private val BEHIND = intArrayOf(R.id.content, R.id.glowSlot, R.id.islandSlot)
        private var loggedDeaf = false
        private var loggedSilent = false
        private var loggedUnstitched = false

        /** The music clock: Choreographer's (System.nanoTime), in milliseconds. */
        internal fun clock(): Double = System.nanoTime() / 1e6
    }
}
