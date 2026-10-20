package com.barontv.home.island

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The island's sums, free of Android types so they can be tested on the JVM. */
internal object IslandMath {

    /** "3:07", as the prototype's mmss: whole seconds, rounded, never negative. */
    fun mmss(ms: Long): String {
        val s = max(0L, (ms + 500) / 1000)
        val sec = (s % 60).toInt()
        return "${s / 60}:${if (sec < 10) "0" else ""}$sec"
    }

    /** The time left, with the prototype's leading hyphen: "-3:13". */
    fun left(positionMs: Long, durationMs: Long): String = "-" + mmss(max(0L, durationMs - positionMs))

    /**
     * Where the song is now: the position the session reported at [atMs], moved on by the time since at
     * its speed while it plays, held inside the song.
     */
    fun position(positionMs: Long, atMs: Long, speed: Float, playing: Boolean, durationMs: Long, nowMs: Long): Long {
        var p = positionMs
        if (playing && speed != 0f && atMs > 0 && nowMs > atMs) p += ((nowMs - atMs) * speed).toLong()
        p = max(0L, p)
        return if (durationMs > 0) min(p, durationMs) else p
    }

    /** The progress line's fill, 0 to 1. */
    fun progress(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * How long (real ms) until either shown time changes, so the times are redrawn exactly then and not
     * every frame. The elapsed time flips as the position passes each half second; the time left flips
     * where the duration's own half seconds fall, which can be a different moment.
     */
    fun msToNextChange(positionMs: Long, durationMs: Long, speed: Float): Long {
        if (speed <= 0f) return 1000
        var d = untilNext(500, positionMs)
        if (durationMs > 0) d = min(d, untilNext(durationMs - 500, positionMs))
        // A millisecond past the boundary, so the rounding has turned when we look.
        return max(16L, (d / speed).toLong() + 1)
    }

    private fun untilNext(boundary: Long, positionMs: Long): Long {
        val d = Math.floorMod(boundary - positionMs, 1000L)
        return if (d == 0L) 1000L else d
    }

    // ---- The equaliser: island.css's isl-eq, four bars breathing between 0.28 and 1 ----------------

    private val BAR_MS = longArrayOf(820, 640, 960, 720)
    private val BAR_DELAY_MS = longArrayOf(0, -320, -150, -500)
    const val BAR_LOW = 0.28f
    const val BAR_REST = 0.22f

    /**
     * The phase of bar [i] at [t] ms since the bars started: 0 at its shortest, 1 at its tallest, before
     * easing. It runs there and back (CSS `alternate`), each way taking the bar's own time.
     */
    fun barPhase(i: Int, t: Long): Float {
        val d = BAR_MS[i]
        val local = t - BAR_DELAY_MS[i]
        val cycle = Math.floorDiv(local, d)
        val frac = (local - cycle * d).toFloat() / d
        return if (cycle % 2 == 0L) frac else 1f - frac
    }

    /** A bar's height as a fraction of its 15 dp, from its eased phase. */
    fun barScale(eased: Float): Float = BAR_LOW + (1f - BAR_LOW) * eased

    // ---- The accent: the cover's most vivid colour, as the prototype's vividColor and --isl-accent ---

    /** The grey the glow falls back to when nothing can be read. */
    const val FALLBACK = 0xFF444444.toInt()

    /**
     * The most saturated, not-too-dark pixel of a 12 x 12 sample of the cover; a nearly grey cover gives
     * its average, lifted (AppAdapter.vividColor, as app.js has it).
     */
    fun vivid(pixels: IntArray): Int {
        if (pixels.isEmpty()) return FALLBACK
        val hsv = FloatArray(3)
        var best = FALLBACK
        var bestScore = -1f
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            toHsv(p, hsv)
            val score = hsv[1] * min(1f, hsv[2] * 1.4f)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return if (bestScore < 0.25f) {
            val n = pixels.size
            toHsv(rgb((r / n.toFloat()).roundToInt(), (g / n.toFloat()).roundToInt(), (b / n.toFloat()).roundToInt()), hsv)
            fromHsv(hsv[0], hsv[1], max(hsv[2], 0.55f))
        } else {
            toHsv(best, hsv)
            fromHsv(hsv[0], min(1f, hsv[1] * 1.1f), max(0.5f, hsv[2]))
        }
    }

    /**
     * island.css's --isl-accent for a cover colour: the same hue at 80% lightness with chroma capped at
     * 0.17, yellows leaned to amber (hue 68) so they don't go olive, and a near-grey cover giving a light
     * grey (oklch 92%).
     */
    fun accent(rgb: Int): Int {
        val lch = accentLch(rgb)
        return fromOklch(lch[0], lch[1], lch[2])
    }

    /** The accent as island.css specifies it, lightness, chroma and hue, before it's drawn in sRGB. */
    fun accentLch(rgb: Int): FloatArray {
        val lch = FloatArray(3)
        toOklch(rgb, lch)
        if (lch[1] < 0.03f) return floatArrayOf(0.92f, 0f, 0f)
        val h = if (lch[2] > 75f && lch[2] < 120f) 68f else lch[2]
        return floatArrayOf(0.80f, min(lch[1], 0.17f), h)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** HSV as app.js's rgbToHsv: hue 0 to 360, saturation and value 0 to 1. */
    fun toHsv(argb: Int, out: FloatArray) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = 0f
        if (d > 0f) {
            h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
        }
        out[0] = (h + 360f) % 360f
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** sRGB to OKLCH through Bjorn Ottosson's OKLab, as app.js's toOklch: lightness, chroma, hue. */
    fun toOklch(argb: Int, out: FloatArray) {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val bigL = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
        out[0] = bigL.toFloat()
        out[1] = hypot(a, bb).toFloat()
        out[2] = ((Math.toDegrees(atan2(bb, a)) + 360.0) % 360.0).toFloat()
    }

    /** OKLCH to an opaque sRGB colour; out-of-gamut channels are clipped, as Chrome draws them. */
    fun fromOklch(l: Float, c: Float, h: Float): Int {
        val hr = Math.toRadians(h.toDouble())
        val a = c * cos(hr)
        val b = c * sin(hr)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1
        val g = -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1
        val bl = -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1
        fun enc(x: Double): Int {
            val v = x.coerceIn(0.0, 1.0)
            val e = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
            return (e * 255).roundToInt().coerceIn(0, 255)
        }
        return rgb(enc(r), enc(g), enc(bl))
    }
}
