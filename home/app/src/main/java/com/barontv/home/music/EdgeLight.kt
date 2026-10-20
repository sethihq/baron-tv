package com.barontv.home.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The edge light, ported from the prototype's drawLight: soft dabs of light round the screen's rounded
 * edge, added together, at the prototype's half resolution (480 x 270, two dp a pixel). Its depth rolls
 * along the edge like a slow tide and pools in the corners, kicks send swells of light from the bottom
 * centre round both sides and the highs send small ones from the top, the bass deepens it, the highs
 * make it sparkle, and in music mode it breathes. Three neighbouring colours from the cover flow round
 * it every 50 s.
 *
 * [frame] works out every dab's place, size and colour into flat arrays, allocating nothing; LightView
 * draws them. Plain Kotlin, no Android, so the maths is tested on the JVM.
 */
internal class EdgeLight(val dabs: Int = DABS) {

    /** Dab centres, in the 480 x 270 light canvas: a few pixels outside the screen, so no edge reads as a line. */
    val x = FloatArray(dabs)
    val y = FloatArray(dabs)

    /** Worked out by [frame]: each dab's radius in canvas pixels, and its colour with its alpha. */
    val radius = FloatArray(dabs)
    val argb = IntArray(dabs)

    private val corner = BooleanArray(dabs)

    /** The three colours, OKLCH (lightness in percent, as the prototype writes them), easing to [goal]. */
    private val now = arrayOf(doubleArrayOf(62.0, 0.2, 300.0), doubleArrayOf(56.0, 0.22, 255.0), doubleArrayOf(70.0, 0.16, 345.0))
    private val goal = arrayOf(now[0].copyOf(), now[1].copyOf(), now[2].copyOf())

    /** The colours round the loop, sampled once whenever they move, so a dab only looks its colour up. */
    private val lut = IntArray(LUT)
    private var lutStale = true

    /** How deep the bass is and how much the highs sparkle, 0 to 1, set by the beat. */
    var bass = 0.3f
    var high = 0f

    /** Music mode: 1 grows the light to full strength, deeper, brighter and breathing; [fullK] eases to it. */
    var full = 0f
    var fullK = 0f

    // Swells: where round the edge they start (0 the top centre, 0.5 the bottom centre), when, how strong.
    private val swellAt = FloatArray(MAX_SWELLS)
    private val swellBorn = DoubleArray(MAX_SWELLS)
    private val swellAmp = FloatArray(MAX_SWELLS)
    var swells = 0
        private set

    private val oklch = DoubleArray(3)

    init {
        // Points round the screen's rounded edge, evenly spaced, clockwise from the top centre, so the
        // bottom centre is halfway. They sit 3 pixels outside the screen; corners are flagged so the light
        // can pool there.
        val o = 3.0
        val r = 10.0 + o // the screen's 20 dp corner at half resolution, pushed out with them
        val w0 = W + 2 * o
        val h0 = H + 2 * o
        val w = w0 - 2 * r
        val h = h0 - 2 * r
        val q = PI / 2 * r
        val total = 2 * w + 2 * h + 4 * q
        for (i in 0 until dabs) {
            var d = ((i.toDouble() / dabs) * total + w / 2) % total
            var px: Double
            var py: Double
            var arc = true
            if (d < w) {
                px = r + d; py = 0.0; arc = false
            } else {
                d -= w
                if (d < q) {
                    val a = -PI / 2 + d / r
                    px = w0 - r + r * cos(a); py = r + r * sin(a)
                } else {
                    d -= q
                    if (d < h) {
                        px = w0; py = r + d; arc = false
                    } else {
                        d -= h
                        if (d < q) {
                            val a = d / r
                            px = w0 - r + r * cos(a); py = h0 - r + r * sin(a)
                        } else {
                            d -= q
                            if (d < w) {
                                px = w0 - r - d; py = h0; arc = false
                            } else {
                                d -= w
                                if (d < q) {
                                    val a = PI / 2 + d / r
                                    px = r + r * cos(a); py = h0 - r + r * sin(a)
                                } else {
                                    d -= q
                                    if (d < h) {
                                        px = 0.0; py = h0 - r - d; arc = false
                                    } else {
                                        d -= h
                                        val a = PI + d / r
                                        px = r + r * cos(a); py = r + r * sin(a)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            x[i] = (px - o).toFloat()
            y[i] = (py - o).toFloat()
            corner[i] = arc
        }
    }

    /**
     * The cover's colour as the light's three: the colour itself, one 55 degrees back round the wheel and
     * one 45 forward, vivid on black, olive leaned to amber. A grey cover gives a soft silver.
     */
    fun setCover(rgb: Int) {
        Colour.rgbToOklch(rgb, oklch)
        setHue(oklch[2], grey = oklch[1] < 0.03)
    }

    /** The three colours round [hue]; a [grey] cover gives them 15% of the chroma, a soft silver. */
    fun setHue(hue: Double, grey: Boolean = false) {
        val k = if (grey) 0.15 else 1.0
        set(goal[0], 62.0, 0.2 * k, Colour.unOlive(hue))
        set(goal[1], 56.0, 0.22 * k, Colour.unOlive(hue + 305.0))
        set(goal[2], 70.0, 0.16 * k, Colour.unOlive(hue + 45.0))
    }

    /** Straight to the cover's colours, for when the light first shows. */
    fun snapColours() {
        for (i in 0 until 3) set(now[i], goal[i][0], goal[i][1], goal[i][2])
        lutStale = true
    }

    /** A swell of light from [at] round the edge (0.5 the bottom centre, 0 the top), [amp] up to 1. */
    fun swell(at: Float, born: Double, amp: Float) {
        if (swells == MAX_SWELLS) drop(0)
        swellAt[swells] = at
        swellBorn[swells] = born
        swellAmp[swells] = amp
        swells++
    }

    fun clearSwells() {
        swells = 0
    }

    /**
     * Works out every dab for the frame at [nowMs]. [dt] is the time since the last frame in seconds: the
     * prototype eased per frame at 60 fps, here the same rates are per second, so a slower TV frame rate
     * doesn't slow them.
     */
    fun frame(nowMs: Double, dt: Double) {
        val time = nowMs / 1000.0
        // The colours ease to the cover's (0.04 a frame at 60 fps).
        val kc = perFrame(0.04, dt)
        for (i in 0 until 3) {
            val a = now[i]
            val g = goal[i]
            if (a[0] != g[0] || a[1] != g[1] || a[2] != g[2]) {
                a[0] = settle(a[0] + (g[0] - a[0]) * kc, g[0], 0.01)
                a[1] = settle(a[1] + (g[1] - a[1]) * kc, g[1], 0.0002)
                val hue = mixHue(a[2], g[2], kc)
                a[2] = if (abs(hueGap(hue, g[2])) < 0.05) g[2] else hue
                lutStale = true
            }
        }
        if (lutStale) fillLut()
        // Swells fade within 2.6 s.
        var i = 0
        while (i < swells) {
            if (nowMs - swellBorn[i] >= 2600.0) drop(i) else i++
        }
        // Music mode eases the light up to full: deeper, brighter, breathing slowly with the song.
        fullK += ((full - fullK) * perFrame(0.03, dt)).toFloat()
        val fk = fullK.toDouble()
        val breath = 1.0 + (0.05 + fk * 0.15) * sin(time * 2.0 * PI / 5.5)
        val deep = 1.0 + bass * 0.5
        val turn = time / 50.0
        val cap = 0.3 + fk * 0.14
        val grow = (1.0 + fk * 0.7) * breath
        val bright = (1.0 + fk * 0.6) * breath
        val sparkleAmp = high * 0.4
        // Fewer dabs than the prototype's 220, each carrying their share of its light.
        val share = PROTOTYPE_DABS.toDouble() / dabs
        for (d in 0 until dabs) {
            val t = d.toDouble() / dabs
            // The tide: three slow waves running round at different speeds, so the light's inner edge rolls.
            val tide = 0.5 * sin(2.0 * PI * 3.0 * t + time * 0.5) +
                0.3 * sin(2.0 * PI * 5.0 * t - time * 0.75 + 1.3) +
                0.2 * sin(2.0 * PI * 8.0 * t + time * 1.2 + 0.4)
            val swell = swellLight(t, nowMs)
            val w = (14.0 * (1.0 + tide * 0.45) * deep * (if (corner[d]) 1.3 else 1.0) + 14.0 * swell) * grow
            val sparkle = 1.0 + sparkleAmp * sin(d * 12.9898 + time * 21.0)
            val alpha = min(1.0, share * min(cap, (0.085 + swell * 0.09) * sparkle * bright))
            radius[d] = w.toFloat()
            val u = t + turn
            val slot = ((u - floor(u)) * LUT).toInt().coerceIn(0, LUT - 1)
            argb[d] = ((alpha * 255.0).roundToInt().coerceIn(0, 255) shl 24) or (lut[slot] and 0xFFFFFF)
        }
    }

    // How much light the swells bring to the point t round the edge: each runs both ways from where it
    // starts, a third of the way round a second, fading as it goes.
    private fun swellLight(t: Double, nowMs: Double): Double {
        var sum = 0.0
        for (s in 0 until swells) {
            val age = ((nowMs - swellBorn[s]) / 1000.0).coerceAtLeast(0.0)
            val amp = swellAmp[s] * exp(-age / 0.85)
            val reach = age * 0.3
            sum += amp * (bump(t, swellAt[s] + reach) + bump(t, swellAt[s] - reach))
        }
        return sum
    }

    private fun drop(index: Int) {
        for (j in index until swells - 1) {
            swellAt[j] = swellAt[j + 1]
            swellBorn[j] = swellBorn[j + 1]
            swellAmp[j] = swellAmp[j + 1]
        }
        swells--
    }

    // The palette round the loop: main, second, third, second, smoothly, in OKLCH, then as sRGB with the
    // canvas's saturate(1.15) already applied.
    private fun fillLut() {
        lutStale = false
        for (j in 0 until LUT) {
            val x4 = j.toDouble() / LUT * 4.0
            val i = x4.toInt()
            val f = x4 - i
            val s = f * f * (3 - 2 * f)
            val a = now[LOOP[i]]
            val b = now[LOOP[(i + 1) % 4]]
            val rgb = Colour.oklchToRgb((a[0] + (b[0] - a[0]) * s) / 100.0, a[1] + (b[1] - a[1]) * s, mixHue(a[2], b[2], s))
            lut[j] = Colour.saturate(rgb, SATURATE)
        }
    }

    /** The current colours, for tests: [index] 0 to 2, as lightness, chroma, hue. */
    fun colour(index: Int): DoubleArray = now[index]

    /** The colours being eased to, for tests. */
    fun goal(index: Int): DoubleArray = goal[index]

    companion object {
        /** The light canvas, half of the prototype's 960 x 540. */
        const val W = 480.0
        const val H = 270.0

        /**
         * The prototype drew 220 dabs. 160 look the same, even on the thinnest stretch of the tide as the
         * light starts to grow; at 130 the inner edge starts to scallop.
         */
        const val DABS = 160
        const val PROTOTYPE_DABS = 220

        /** The canvas was drawn with saturate(1.15) and blur(2.5px); the blur lives in the dab bitmap. */
        const val SATURATE = 1.15

        private const val LUT = 256
        private const val MAX_SWELLS = 10
        private val LOOP = intArrayOf(0, 1, 2, 1)

        private fun set(c: DoubleArray, l: Double, ch: Double, h: Double) {
            c[0] = l
            c[1] = ch
            c[2] = h
        }

        // A swell's front at c, round the loop: a narrow bump either side of it.
        private fun bump(t: Double, c: Double): Double {
            var d = abs(t - (c - floor(c)))
            d = min(d, 1.0 - d)
            val e = d * d / 0.0018
            // Past 12 the bump is under a millionth: not worth an exp.
            return if (e > 12.0) 0.0 else exp(-e)
        }

        /** The share of the way a per-frame ease of [k] at 60 fps covers in [dt] seconds. */
        fun perFrame(k: Double, dt: Double): Double = 1.0 - exp(dt * 60.0 * ln(1.0 - k))

        /** The short way round the colour wheel from [a] to [b], [k] of the way. */
        fun mixHue(a: Double, b: Double, k: Double): Double = (((a + hueGap(a, b) * k) % 360.0) + 360.0) % 360.0

        /** From hue [a] to hue [b] the short way round, in degrees, -180 to 180. */
        fun hueGap(a: Double, b: Double): Double = ((((b - a + 540.0) % 360.0) + 360.0) % 360.0) - 180.0

        private fun settle(v: Double, goal: Double, near: Double) = if (abs(goal - v) < near) goal else v
    }
}
