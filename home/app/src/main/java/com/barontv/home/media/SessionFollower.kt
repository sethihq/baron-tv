package com.barontv.home.media

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.NowPlayingHub

/**
 * Follows a set of media sessions and publishes what's playing to NowPlayingHub: the session that is
 * playing (else the most recent one that is paused), republished whenever its song, state or position
 * changes. Two things feed it the sessions: notification access (NowPlayingListener) where the TV allows
 * it, and on this low-RAM TV, which doesn't, the session tokens in music apps' notifications
 * (SessionTokens, through the Input key's accessibility service).
 *
 * Everything system-facing runs on one background thread of its own: the callbacks, scaling the art.
 * Only the finished NowPlaying crosses to the main thread.
 */
class SessionFollower(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null

    // Read on the worker to drop late callbacks once the listener has been let go.
    @Volatile
    private var worker: Handler? = null

    // ---- Touched on the worker thread only ----
    private var audio: AudioManager? = null
    private var watches: List<Watch> = emptyList()
    private var contentTypes: List<Int> = emptyList()
    private var art: Bitmap? = null
    private var artSignature = 0L
    private val labels = HashMap<String, String>()

    /**
     * One watcher on every active session, not just the followed one: another app starting to play is what
     * moves the island over to it. Each keeps the state and metadata its callbacks bring, so an update
     * doesn't ask every session again (the metadata carries the cover, a fresh copy each time it's read).
     */
    private inner class Watch(val controller: MediaController) : MediaController.Callback() {
        var state: PlaybackState? = controller.playbackState
        private var meta: MediaMetadata? = null
        private var metaRead = false

        val metadata: MediaMetadata?
            get() {
                if (!metaRead) {
                    meta = controller.metadata
                    metaRead = true
                }
                return meta
            }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            this.state = state
            update()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            meta = metadata
            metaRead = true
            update()
        }

        override fun onSessionDestroyed() {
            state = null
            update()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            contentTypes = typesOf(configs)
            update()
        }
    }

    fun start() {
        if (worker != null) return
        val t = HandlerThread("now-playing", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        val h = Handler(t.looper)
        thread = t
        worker = h
        h.post {
            context.getSystemService(AudioManager::class.java)?.let { am ->
                am.registerAudioPlaybackCallback(playbackCallback, h)
                audio = am
                contentTypes = typesOf(am.activePlaybackConfigurations)
            }
        }
    }

    /** Follows [list] from now on (from any thread). */
    fun follow(list: List<MediaController>?) {
        worker?.post { followNow(list) }
    }

    /** The worker's own handler, for registering system listeners on it. */
    val handler: Handler? get() = worker

    /** Lets everything go, then says nothing is playing (after any update still queued on the worker). */
    fun stop() {
        val h = worker ?: return
        val t = thread
        worker = null
        thread = null
        NowPlayingControls.attach(null, null)
        h.post {
            audio?.unregisterAudioPlaybackCallback(playbackCallback)
            audio = null
            for (w in watches) w.controller.unregisterCallback(w)
            watches = emptyList()
            art = null
            main.post { NowPlayingHub.publish(null) }
            t?.quitSafely()
        }
    }

    private fun followNow(list: List<MediaController>?) {
        val h = worker ?: return
        for (w in watches) w.controller.unregisterCallback(w)
        watches = try {
            list.orEmpty().map { c -> Watch(c).also { c.registerCallback(it, h) } }
        } catch (e: RuntimeException) {
            // A session went while we looked: its removal brings another list.
            emptyList()
        }
        update()
    }

    private fun update() {
        val h = worker ?: return
        val now = try {
            val at = MediaPick.pick(watches.map { it.state?.state ?: MediaPick.STATE_NONE })
            val w = at?.let { watches[it] }
            NowPlayingControls.attach(w?.controller, if (w != null) h else null)
            w?.let { build(it) }
        } catch (e: RuntimeException) {
            // A session died mid-read: the destroyed callback brings the next update.
            null
        }
        main.post { if (worker != null) NowPlayingHub.publish(now) }
    }

    private fun build(w: Watch): NowPlaying? {
        val state = w.state ?: return null
        if (!MediaPick.shows(state.state)) return null
        val meta = w.metadata
        val pkg = w.controller.packageName
        val title = meta.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: labelOf(pkg)
        val artist = meta.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val speed = when (state.state) {
            PlaybackState.STATE_PLAYING -> if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> state.playbackSpeed
            // Buffering or skipping: the position holds still until the song moves again.
            else -> 0f
        }
        return NowPlaying(
            packageName = pkg,
            title = title,
            artist = artist,
            art = artOf(meta),
            playing = MediaPick.playing(state.state),
            positionMs = state.position,
            positionAtMs = state.lastPositionUpdateTime,
            speed = speed,
            durationMs = maxOf(0L, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L),
            isMusic = MediaPick.isMusic(pkg, contentTypes),
        )
    }

    private fun MediaMetadata?.text(key: String): String? = this?.getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The cover at most 256 px a side. Apps resend their metadata often, each time with a fresh copy of
     * the same picture, so a cheap signature (size and a few pixels) keeps the scaled one we already have.
     */
    private fun artOf(meta: MediaMetadata?): Bitmap? {
        val src = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (src == null) {
            art = null
            artSignature = 0L
            return null
        }
        return try {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val sig = signatureOf(soft)
            if (sig == artSignature && art != null) return art
            val (w, h) = MediaPick.fit(soft.width, soft.height)
            val scaled = if (w == soft.width && h == soft.height) soft else Bitmap.createScaledBitmap(soft, w, h, true)
            art = scaled
            artSignature = sig
            scaled
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun signatureOf(b: Bitmap): Long {
        var sig = (b.width.toLong() shl 32) xor b.height.toLong()
        val w = b.width
        val h = b.height
        if (w == 0 || h == 0) return sig
        for (i in 1..4) for (j in 1..4) {
            sig = sig * 31 + b.getPixel(w * i / 5, h * j / 5)
        }
        return sig
    }

    /** The app's own name, for a session that gives no title. */
    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun typesOf(configs: List<AudioPlaybackConfiguration>?): List<Int> =
        configs.orEmpty().map { it.audioAttributes.contentType }
}
