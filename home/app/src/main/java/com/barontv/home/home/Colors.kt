package com.barontv.home.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The colour maths the prototype leaves to CSS: sRGB to OKLab and OKLCH and back (Bjorn Ottosson's
 * OKLab, as browsers implement it). Out-of-gamut colours are clipped per channel, as Chrome draws them.
 * Plain doubles in caller-owned arrays, so the glow can ease its colours every frame without allocating.
 */
object Oklab {
    private fun toLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun toGamma(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    }

    /** sRGB channels (0 to 255) to OKLab: [L, a, b] into [out]. */
    fun fromRgb(r: Int, g: Int, b: Int, out: DoubleArray) {
        val lr = toLinear(r / 255.0)
        val lg = toLinear(g / 255.0)
        val lb = toLinear(b / 255.0)
        val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    }

    /** OKLab to an opaque sRGB colour int, clipped to the sRGB gamut. */
    fun toArgb(lightness: Double, a: Double, b: Double): Int {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        val r = toGamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
        val g = toGamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
        val bl = toGamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
        return (0xFF shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (bl * 255).roundToInt()
    }

    /** OKLCH (L 0 to 1, chroma, hue in degrees) to an opaque sRGB colour int. */
    fun oklch(lightness: Double, chroma: Double, hue: Double): Int {
        val h = Math.toRadians(hue)
        return toArgb(lightness, chroma * cos(h), chroma * sin(h))
    }

    /** sRGB to OKLCH: [L, C, h] into [out], as the prototype's toOklch(). */
    fun lchFromRgb(r: Int, g: Int, b: Int, out: DoubleArray) {
        fromRgb(r, g, b, out)
        val c = hypot(out[1], out[2])
        val h = (Math.toDegrees(atan2(out[2], out[1])) + 360) % 360
        out[1] = c
        out[2] = h
    }
}

/**
 * The glow's three colours for an artwork's vivid colour, as the prototype's setGlow(): every glow at one
 * lightness with capped chroma, so no app's glow is louder than another's; yellows lean to amber, since a
 * dark yellow on black reads as olive. A deep, saturated core and a darker mid tone rise from below the
 * dock; the far corner takes a tone a step round the colour wheel, for depth.
 *
 * Each colour is OKLCH plus alpha: [L, C, h, alpha].
 */
class GlowColors(val core: DoubleArray, val mid: DoubleArray, val far: DoubleArray) {
    companion object {
        fun forArt(rgb: Int): GlowColors {
            val lch = DoubleArray(3)
            Oklab.lchFromRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, lch)
            val c = lch[1]
            val h = if (lch[2] > 75 && lch[2] < 120) 68.0 else lch[2]
            val chroma = if (c < 0.03) 0.0 else min(c, 0.16)
            val deep = if (chroma > 0) min(chroma * 1.15, 0.18) else 0.0
            return GlowColors(
                core = doubleArrayOf(0.50, deep, h, 0.6),
                mid = doubleArrayOf(0.40, deep * 0.9, h, 0.26),
                far = doubleArrayOf(0.46, deep * 0.75, (h + 335) % 360, 0.16),
            )
        }
    }
}

/**
 * The artwork's colour for the glow, as the prototype's vividColor() (itself the launcher's
 * AppAdapter.vividColor): the most saturated, not-too-dark pixel of the art at 12 x 12, made a touch more
 * vivid; a nearly grey picture (a white banner, say) gives its average, lifted.
 */
object VividColor {
    /** No art yet, or art that can't be read: a grey glow. */
    const val FALLBACK = 0xFF444444.toInt()

    private const val SIDE = 12

    /** Picks from [count] ARGB pixels: the art already shrunk to 12 x 12. */
    fun pick(pixels: IntArray, count: Int = pixels.size): Int {
        if (count == 0) return FALLBACK
        val hsv = FloatArray(3)
        var best = FALLBACK
        var bestScore = -1f
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        for (i in 0 until count) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sr += r
            sg += g
            sb += b
            rgbToHsv(r.toFloat(), g.toFloat(), b.toFloat(), hsv)
            val score = hsv[1] * min(1f, hsv[2] * 1.4f)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        if (bestScore < 0.25f) {
            rgbToHsv((sr / count).toFloat(), (sg / count).toFloat(), (sb / count).toFloat(), hsv)
            return hsvToRgb(hsv[0], hsv[1], max(hsv[2], 0.55f))
        }
        rgbToHsv(((best shr 16) and 0xFF).toFloat(), ((best shr 8) and 0xFF).toFloat(), (best and 0xFF).toFloat(), hsv)
        return hsvToRgb(hsv[0], min(1f, hsv[1] * 1.1f), max(0.5f, hsv[2]))
    }

    /**
     * Shrinks [art] to 12 x 12 by averaging (filtered to 48 x 48 first, then 4 x 4 blocks), so a small
     * logo on a plain tile still counts, then picks. Not for onDraw: it allocates a small bitmap.
     */
    fun of(art: Bitmap?): Int {
        if (art == null || art.isRecycled || art.width == 0 || art.height == 0) return FALLBACK
        return try {
            val n = SIDE * 4
            val small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(art, Rect(0, 0, art.width, art.height), Rect(0, 0, n, n), Paint(Paint.FILTER_BITMAP_FLAG))
            val big = IntArray(n * n)
            small.getPixels(big, 0, n, 0, 0, n, n)
            small.recycle()
            pick(boxDown(big, n, 4))
        } catch (e: RuntimeException) {
            FALLBACK
        }
    }

    /** Averages [block] x [block] squares of a [side] x [side] image. */
    internal fun boxDown(pixels: IntArray, side: Int, block: Int): IntArray {
        val out = side / block
        val result = IntArray(out * out)
        val area = block * block
        for (oy in 0 until out) {
            for (ox in 0 until out) {
                var r = 0
                var g = 0
                var b = 0
                for (y in oy * block until oy * block + block) {
                    for (x in ox * block until ox * block + block) {
                        val p = pixels[y * side + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                result[oy * out + ox] = (0xFF shl 24) or ((r / area) shl 16) or ((g / area) shl 8) or (b / area)
            }
        }
        return result
    }

    /** The prototype's rgbToHsv: hue in degrees, saturation and value 0 to 1. */
    internal fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)) / 255f
        val mn = min(r, min(g, b)) / 255f
        val d = mx - mn
        var h = 0f
        if (d > 0) {
            h = when (mx) {
                r / 255f -> 60f * (((g - b) / 255f / d) % 6f)
                g / 255f -> 60f * ((b - r) / 255f / d + 2f)
                else -> 60f * ((r - g) / 255f / d + 4f)
            }
        }
        out[0] = (h + 360f) % 360f
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    /** The prototype's hsvToRgb, rounded to whole channels. */
    internal fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(n: Float) = ((n + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/** Mixes two colours channel by channel in sRGB, as a CSS colour transition between two greys does. */
internal fun mixColor(from: Int, to: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
