package com.barontv.home.media

import android.media.session.MediaController
import android.os.Handler

/**
 * The buttons for whatever NowPlayingHub is showing: play, pause, next and previous on the session the
 * listener follows. Every call goes to the listener's own thread, since each one is a call into the
 * system that the main thread shouldn't wait on. Without notification access (or with nothing
 * playing) there's no session and the calls do nothing.
 */
object NowPlayingControls {
    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var handler: Handler? = null

    /** The listener hands over the session it follows, and its thread; null and null when it stops. */
    internal fun attach(controller: MediaController?, handler: Handler?) {
        this.controller = controller
        this.handler = handler
    }

    /** True while there's a session to send to. */
    val available: Boolean get() = controller != null && handler != null

    fun play() = send(PLAY)

    fun pause() = send(PAUSE)

    fun next() = send(NEXT)

    fun previous() = send(PREVIOUS)

    private fun send(action: Int) {
        val c = controller ?: return
        val h = handler ?: return
        h.post {
            try {
                val t = c.transportControls
                when (action) {
                    PLAY -> t.play()
                    PAUSE -> t.pause()
                    NEXT -> t.skipToNext()
                    else -> t.skipToPrevious()
                }
            } catch (e: RuntimeException) {
                // The app went away between the press and the call: the next update hides the island.
            }
        }
    }

    private const val PLAY = 0
    private const val PAUSE = 1
    private const val NEXT = 2
    private const val PREVIOUS = 3
}
