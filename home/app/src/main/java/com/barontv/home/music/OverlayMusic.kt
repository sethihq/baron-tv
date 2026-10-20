package com.barontv.home.music

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.WindowManager
import kotlin.random.Random

/**
 * Music mode over the music app itself. On this TV the firmware force-stops whatever app you leave when
 * Home comes up (CVTE's black_white_list_manager: Spotify isn't on its keep-alive list, which lives on the
 * read-only system partition), so music never plays behind the home screen and the home screen's own
 * music mode can't start. Instead, while a music app plays in front and the remote rests [IDLE_MS], this
 * lays the same dim, edge light and beat bot over it, in an accessibility overlay window (no extra
 * permission), and the music app keeps playing underneath. The first key only wakes it.
 *
 * Driven by the Input key's accessibility service, which sees every key and which app is in front.
 * There's no media session to read here (no notification access on low-RAM Android), so the light keeps
 * its own colours, and "music is playing" is the music app in front with music audio active.
 */
class OverlayMusic(private val context: Context) {
    private val engine = MusicEngine(Random(System.nanoTime()))
    private val hearing = Hearing()
    private val ear = BeatEar(hearing)
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val windows = context.getSystemService(WindowManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var view: MusicLayer? = null
    private var added = false
    private var looping = false
    private var front = ""
    private var lastKey = now()
    private var swallowUp = -1
    private var loggedDeaf = false
    private val lamps = LampSync()

    init {
        engine.onKick = { lamps.kick(engine.light) }
    }

    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private val check = object : Runnable {
        override fun run() {
            tick()
            if (isMusicApp(front)) main.postDelayed(this, CHECK_MS)
        }
    }

    val isOn: Boolean get() = engine.on

    /**
     * Every key, before any app sees it. Returns true to swallow it: the key that wakes music mode (its
     * down and its up), so it only wakes the screen.
     */
    fun onKey(event: KeyEvent): Boolean {
        lastKey = now()
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && engine.on) {
            swallowUp = event.keyCode
            exit()
            return true
        }
        return false
    }

    /** A key was pressed on the phone app (it reaches the TV injected, past [onKey]): as a key here. */
    fun onRemoteActivity() {
        lastKey = now()
        if (engine.on) exit()
    }

    /** The app in front changed. Music mode only waits while a music app is in front. */
    fun onFront(packageName: String) {
        // Our own overlay (and the volume panel's system UI) appearing doesn't change who's in front.
        if (packageName == front || packageName == context.packageName || packageName == SYSTEM_UI) return
        front = packageName
        main.removeCallbacks(check)
        if (isMusicApp(packageName)) main.postDelayed(check, CHECK_MS) else if (engine.on) exit()
    }

    fun release() {
        main.removeCallbacks(check)
        lamps.stop()
        engine.off()
        ear.stop()
        finish()
        view?.release()
        view = null
    }

    // Once a second while a music app is in front: in after the rest with music sounding, out when it stops.
    private fun tick() {
        val sounding = audio?.isMusicActive == true
        if (engine.on) {
            if (!sounding) exit()
        } else if (sounding && now() - lastKey >= IDLE_MS) {
            enter()
        }
    }

    private fun enter() {
        if (engine.on) return
        val v = view ?: MusicLayer(context, engine).also { view = it }
        if (!added) {
            try {
                windows?.addView(v, layoutParams())
                added = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Can't show music mode over the app: $e")
                return
            }
        }
        engine.still = !ValueAnimator.areAnimatorsEnabled()
        val t = now()
        engine.enter(t)
        lamps.start()
        if (engine.still) {
            engine.frame(t)
            v.render(false)
            return
        }
        engine.hearing = ear.start(context)
        if (!engine.hearing && !loggedDeaf) {
            loggedDeaf = true
            Log.w(TAG, "Can't hear the TV (${ear.failure}); keeping a steady ${MusicEngine.TEMPO_BPM.toInt()} BPM")
        }
        loop()
    }

    private fun exit() {
        if (!engine.on) return
        engine.exit(now())
        lamps.stop()
        ear.stop()
        engine.hearing = false
        if (engine.settled) finish() else loop()
    }

    private fun loop() {
        if (looping) return
        looping = true
        choreographer.postFrameCallback(frame)
    }

    private fun finish() {
        if (looping) choreographer.removeFrameCallback(frame)
        looping = false
        val v = view
        if (added && v != null) {
            try {
                windows?.removeView(v)
            } catch (e: RuntimeException) {
                // Already gone with the service.
            }
        }
        added = false
    }

    private fun onFrame(frameTimeNanos: Long) {
        looping = false
        val v = view ?: return
        val t = frameTimeNanos / 1e6
        if (engine.on && ear.listening) {
            ear.hear(t, engine)
            val deaf = ear.failing || (hearing.silentSince >= 0 && t - hearing.silentSince > SILENT_MS)
            engine.hearing = !deaf
        }
        engine.frame(t)
        // Over another app nothing behind can be hidden, so the dim is always drawn.
        v.render(false)
        if (engine.settled) {
            finish()
            return
        }
        loop()
    }

    // Full screen over everything, never taking focus or touches (keys come through the service), and
    // keeping the screen on while it shows.
    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { title = "Music mode" }

    private companion object {
        const val TAG = "BaronMusic"
        const val IDLE_MS = 8000.0
        const val CHECK_MS = 1000L
        const val SILENT_MS = 6000.0
        const val SYSTEM_UI = "com.android.systemui"

        /** Apps whose sound is music (not a film's soundtrack): music mode waits while one is in front. */
        val MUSIC_APPS = setOf(
            "com.spotify.tv.android",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.jio.media.jiobeats",
            "com.gaana",
            "com.bsbportal.music",
            "com.amazon.music.tv",
            "com.apple.android.music",
        )

        fun isMusicApp(pkg: String) = pkg in MUSIC_APPS

        fun now(): Double = System.nanoTime() / 1e6
    }
}
