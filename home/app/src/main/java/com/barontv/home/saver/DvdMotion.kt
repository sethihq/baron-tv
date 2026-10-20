package com.barontv.home.saver

import com.barontv.home.music.Colour
import com.barontv.home.music.CubicBezier
import kotlin.math.min
import kotlin.random.Random

/**
 * The prototype's DVD screensaver motion (startSaver, stepSaver, nextSaverColor, randomSpot): the logo
 * glides 105 dp a second along each axis on the prototype's 960 x 540 screen, bounces off every edge in
 * a new colour, and pulses on an exact corner hit. With motion off it jumps to a new spot every 10 s.
 * Times are milliseconds. Plain Kotlin, so the bounces are tested on the JVM.
 */
internal class DvdMotion(private val random: Random) {
    var x = 0.0
    var y = 0.0
    var vx = 0.0
    var vy = 0.0
    var colour = 0
    private var last = 0.0

    /** When the last corner hit was, for the pulse. */
    var cornerAt = -1e9
        private set

    fun start(now: Double) {
        randomSpot()
        vx = if (random.nextDouble() < 0.5) -SPEED else SPEED
        vy = if (random.nextDouble() < 0.5) -SPEED else SPEED
        nextColour()
        last = now
    }

    /** Moves to [now]; returns what it hit: [NONE], [EDGE] or [CORNER]. */
    fun step(now: Double): Int {
        val dt = min(0.05, (now - last) / 1000.0)
        last = now
        val maxX = SCREEN_W - LOGO_W
        val maxY = SCREEN_H - LOGO_H
        x += vx * dt
        y += vy * dt
        var hitX = false
        var hitY = false
        if (x <= 0 || x >= maxX) {
            x = x.coerceIn(0.0, maxX)
            vx = if (x == 0.0) SPEED else -SPEED
            hitX = true
        }
        if (y <= 0 || y >= maxY) {
            y = y.coerceIn(0.0, maxY)
            vy = if (y == 0.0) SPEED else -SPEED
            hitY = true
        }
        if (hitX || hitY) nextColour()
        // The corner everyone waits for.
        if (hitX && hitY) {
            cornerAt = now
            return CORNER
        }
        return if (hitX || hitY) EDGE else NONE
    }

    /** With motion off: somewhere new, in a new colour. */
    fun jump() {
        randomSpot()
        nextColour()
    }

    /** The corner pulse: up to 1.18 and back over 420 ms on the prototype's ease-out. */
    fun scale(now: Double): Float {
        val t = (now - cornerAt) / PULSE_MS
        if (t < 0 || t >= 1) return 1f
        val p = CubicBezier.EASE_OUT.at(t)
        return (if (p < 0.5) 1 + 0.18 * (p / 0.5) else 1.18 - 0.18 * ((p - 0.5) / 0.5)).toFloat()
    }

    /** Never the same colour twice in a row. */
    fun nextColour() {
        colour = (colour + 1 + random.nextInt(COLOURS.size - 1)) % COLOURS.size
    }

    private fun randomSpot() {
        x = random.nextDouble() * (SCREEN_W - LOGO_W)
        y = random.nextDouble() * (SCREEN_H - LOGO_H)
    }

    companion object {
        const val SCREEN_W = 960.0
        const val SCREEN_H = 540.0
        const val LOGO_W = 150.0
        const val LOGO_H = 78.0
        const val SPEED = 105.0
        const val JUMP_MS = 10_000L
        const val PULSE_MS = 420.0

        const val NONE = 0
        const val EDGE = 1
        const val CORNER = 2

        /** Vivid tones on black, oklch(72% 0.19 h); every bounce picks another one. */
        val COLOURS = intArrayOf(25, 60, 95, 145, 195, 250, 300, 340).map { Colour.oklchToRgb(0.72, 0.19, it.toDouble()) }.toIntArray()
    }
}
