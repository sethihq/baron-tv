package com.barontv.home.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * The TV's media volume, for the remote's volume keys and the phone's bridge, with the home's own bar
 * (VolumeOverlay) as the only feedback: every change here is made with no flags, so Android asks for no
 * volume UI of its own, and the bar shows the result. The music stream runs 0 to 100 on this TV.
 *
 * AudioManager's calls go into the system, so they never run on the main thread: the keys' on this
 * class's own thread, the bridge's on its request threads, and one lock keeps a read-then-step from
 * interleaving with another.
 *
 * It also follows changes made anywhere else (an app setting its volume; or the firmware, if it applies
 * the remote's keys before this service sees them) and shows the bar for those too, except while the home
 * screen is in front, where the side dock's own Sound bar is the volume.
 */
internal class TvVolume(private val context: Context, private val homeInFront: () -> Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("volume").also { it.start() }
    private val worker = Handler(thread.looper)
    private val overlay = VolumeOverlay(context)
    private val lock = Any()

    @Volatile
    private var lastOwnChange = Long.MIN_VALUE / 2
    private var toldKeys = false
    private var toldOutside = false

    // AudioManager's hidden VOLUME_CHANGED_ACTION and STREAM_MUTE_CHANGED_ACTION, sent to every receiver.
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM, STREAM) != STREAM) return
            // Our own changes show the bar already, and their echoes come within moments.
            if (SystemClock.uptimeMillis() - lastOwnChange < ECHO_MS) return
            if (!toldOutside) {
                toldOutside = true
                Log.i(TAG, "The volume changed outside the home (an app, or the firmware taking the remote's keys first)")
            }
            if (homeInFront()) return
            val level = read()
            main.post { overlay.show(level) }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED)
            addAction(MUTE_CHANGED)
        }
        // System broadcasts only; they arrive on the volume thread.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(changes, filter, null, worker, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(changes, filter, null, worker)
    }

    /** The level now, without showing the bar. Any thread but the main one. */
    fun read(): VolumeLevel = synchronized(lock) { readLocked() }

    /**
     * One step of five up or down. Muted, a step unmutes from the level it had, as Android and most TVs
     * do: AudioService unmutes the stream when it's set above 0 (and mutes it at 0).
     */
    fun step(up: Boolean): VolumeLevel = change { am ->
        val max = am.getStreamMaxVolume(STREAM)
        am.setStreamVolume(STREAM, VolumeSteps.stepIndex(am.getStreamVolume(STREAM), max, up), 0)
    }

    /** Sets the level to [percent] (the phone's slider). */
    fun set(percent: Int): VolumeLevel = change { am ->
        am.setStreamVolume(STREAM, VolumeSteps.index(percent, am.getStreamMaxVolume(STREAM)), 0)
    }

    /** Mute on or off. Unmuting at 0 also brings it up one step, or there'd still be nothing to hear. */
    fun toggleMute(): VolumeLevel = change { am ->
        if (am.isStreamMute(STREAM)) {
            am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
            if (am.getStreamVolume(STREAM) == 0) {
                am.setStreamVolume(STREAM, VolumeSteps.stepIndex(0, am.getStreamMaxVolume(STREAM), up = true), 0)
            }
        } else {
            am.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0)
        }
    }

    /** Shows the bar at the current level (the phone's Sound shortcut). */
    fun showNow() {
        worker.post {
            val level = read()
            main.post { overlay.show(level) }
        }
    }

    /**
     * The remote's volume keys (main thread, from the accessibility service): true when it's one of them,
     * which is then this class's alone, its press and its release, so the TV's own handling never sees it.
     */
    fun onKey(event: KeyEvent): Boolean {
        val action = VolumeKeys.action(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount) ?: return false
        if (!toldKeys) {
            toldKeys = true
            Log.i(TAG, "The remote's volume keys reach the home")
        }
        when (action) {
            VolumeKey.UP -> worker.post { step(up = true) }
            VolumeKey.DOWN -> worker.post { step(up = false) }
            VolumeKey.MUTE -> worker.post { toggleMute() }
            VolumeKey.NOTHING -> Unit
        }
        return true
    }

    fun release() {
        try {
            context.unregisterReceiver(changes)
        } catch (e: IllegalArgumentException) {
            // Never registered: nothing to undo.
        }
        thread.quitSafely()
        overlay.release()
    }

    private fun readLocked(): VolumeLevel {
        val am = audio ?: return VolumeLevel(0, false)
        val max = am.getStreamMaxVolume(STREAM)
        return VolumeLevel(VolumeSteps.percent(am.getStreamVolume(STREAM), max), am.isStreamMute(STREAM))
    }

    /**
     * Makes a change under the lock, reads the result, and shows it on the bar. The changes pass 0 for
     * their flags: no FLAG_SHOW_UI and no sound, so the home's bar is the only feedback.
     */
    private inline fun change(block: (AudioManager) -> Unit): VolumeLevel {
        val level = synchronized(lock) {
            audio?.let {
                lastOwnChange = SystemClock.uptimeMillis()
                block(it)
            }
            readLocked()
        }
        main.post { overlay.show(level) }
        return level
    }

    private companion object {
        const val TAG = "BaronVolume"
        const val STREAM = AudioManager.STREAM_MUSIC
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        /** A change of ours echoes back as a broadcast within this. */
        const val ECHO_MS = 600L
    }
}
