package com.barontv.home.volume

import android.view.KeyEvent
import kotlin.math.roundToInt

/** The TV's media volume as the bridge and the bar show it: 0 to 100, and whether it's muted. */
data class VolumeLevel(val percent: Int, val muted: Boolean)

/** The volume's sums, free of Android so they're tested on the JVM. */
internal object VolumeSteps {
    /** The remote's and the phone's steps, in percent. */
    const val STEP = 5

    /** The music stream's [index] of [max] as a percent, 0 to 100. */
    fun percent(index: Int, max: Int): Int = if (max <= 0) 0 else (index * 100f / max).roundToInt().coerceIn(0, 100)

    /** The stream index nearest [percent], which is kept to 0..100 first. */
    fun index(percent: Int, max: Int): Int =
        if (max <= 0) 0 else (percent.coerceIn(0, 100) * max / 100f).roundToInt().coerceIn(0, max)

    /**
     * One step up or down on the grid of fives: 35 goes to 40 or 30, and a level off the grid (37, from
     * the phone's slider or the TV's own keys) to the next five that way, 40 or 35. Never past 0 or 100.
     */
    fun stepPercent(percent: Int, up: Boolean): Int {
        val p = percent.coerceIn(0, 100)
        val next = if (up) (p / STEP + 1) * STEP else ((p + STEP - 1) / STEP - 1) * STEP
        return next.coerceIn(0, 100)
    }

    /** One step from stream [index] of [max]; on a coarse stream it still moves at least one index. */
    fun stepIndex(index: Int, max: Int, up: Boolean): Int {
        if (max <= 0) return 0
        val from = index.coerceIn(0, max)
        var next = index(stepPercent(percent(from, max), up), max)
        if (next == from) next = from + if (up) 1 else -1
        return next.coerceIn(0, max)
    }
}

/** What one of the remote's volume keys does. */
internal enum class VolumeKey { UP, DOWN, MUTE, NOTHING }

internal object VolumeKeys {
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    /**
     * What a volume key's event does, or null for any other key. Every press of Up and Down steps, and so
     * does every repeat the remote sends while one is held; Mute toggles once per press, never on its
     * repeats. A key's release does nothing, but it's still the volume's (taken, so the TV never sees it).
     */
    fun action(keyCode: Int, down: Boolean, repeatCount: Int): VolumeKey? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> if (down) VolumeKey.UP else VolumeKey.NOTHING
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (down) VolumeKey.DOWN else VolumeKey.NOTHING
        KeyEvent.KEYCODE_VOLUME_MUTE -> if (down && repeatCount == 0) VolumeKey.MUTE else VolumeKey.NOTHING
        else -> null
    }
}

/**
 * Where the bar sits on screen: exactly where the side dock does (activity_home.xml's sideDockSlot,
 * 232 x 371.6 dp at 672, 32 of the prototype's 960 x 540), as a fraction of the screen, since the
 * accessibility service's windows aren't laid out at the home's 213 dpi.
 */
internal object VolumePlace {
    private const val SCREEN_W = 960f
    private const val SCREEN_H = 540f
    private const val LEFT = 672f
    private const val TOP = 32f
    const val WIDTH = 232f
    private const val HEIGHT = 371.6f

    /** The bar slides in from this far to the right (dp), as the side dock's views do. */
    private const val SLIDE = 14f

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** The box on a screen [screenW] x [screenH] px, each edge rounded on its own so it lands as the dock's does. */
    fun box(screenW: Int, screenH: Int): Box {
        val sx = screenW / SCREEN_W
        val sy = screenH / SCREEN_H
        return Box((LEFT * sx).roundToInt(), (TOP * sy).roundToInt(), ((LEFT + WIDTH) * sx).roundToInt(), ((TOP + HEIGHT) * sy).roundToInt())
    }

    /** The slide's distance in px; the window reaches that much past the box on the right, so nothing is cut off. */
    fun slide(screenW: Int): Int = (SLIDE * screenW / SCREEN_W).roundToInt()
}
