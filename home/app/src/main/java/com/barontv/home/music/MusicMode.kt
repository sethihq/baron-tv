package com.barontv.home.music

import android.app.Activity
import android.widget.FrameLayout

/**
 * Music mode: while music plays and the remote rests (8 s), the screen dims to black and only the edge
 * light shows, breathing with the song, with the beat bot dancing in the middle. It owns the whole
 * feature: the idle timer, the dimming, the light, the beat detection and the bot, drawn in [layer].
 * (Placeholder: the music part replaces this.)
 */
class MusicMode(private val activity: Activity, private val layer: FrameLayout) {
    val isOn: Boolean get() = false

    /** Any key or touch: restarts the idle clock. Returns true if it woke music mode (swallow that key). */
    fun onUserActivity(): Boolean = false

    /** The home screen is in front (onResume) or not (onPause): music mode only runs while it is. */
    fun setForeground(foreground: Boolean) {}

    /** The activity is going away: release the visualizer and everything else. */
    fun release() {}
}
