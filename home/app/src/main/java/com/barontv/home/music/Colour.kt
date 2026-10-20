package com.barontv.home.music

import kotlin.math.PI
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
 * Colour maths for the light and the screensaver, as the prototype does it: OKLCH through Bjorn
 * Ottosson's OKLab, and sRGB clipped channel by channel where a colour falls outside it, which is what
 * Chrome drew for the prototype's vivid colours (oklch(72% 0.19 25) came out as 255, 106, 101).
 * Plain Kotlin, no Android, so the tests run on the JVM.
 */
internal object Colour {

    /** OKLCH (lightness 0 to 1, as CSS's percentage over 100; hue in degrees) to an opaque sRGB int. */
    fun oklchToRgb(lightness: Double, chroma: Double, hue: Double): Int {
        val h = hue * PI / 180.0
        val a = chroma * cos(h)
        val b = chroma * sin(h)
        val l1 = lightness + 0.3963377774 * a + 0.2158037573 * b
        val m1 = lightness - 0.1055613458 * a - 0.0638541728 * b
        val s1 = lightness - 0.0894841775 * a - 1.2914855480 * b
        val l = l1 * l1 * l1
        val m = m1 * m1 * m1
        val s = s1 * s1 * s1
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return rgb(encode(r), encode(g), encode(bl))
    }

    /** An sRGB colour to OKLCH: [lightness 0 to 1, chroma, hue in degrees], as the prototype's toOklch. */
    fun rgbToOklch(rgb: Int, out: DoubleArray) {
        val r = decode(red(rgb))
        val g = decode(green(rgb))
        val b = decode(blue(rgb))
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val ll = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s
        val aa = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
        out[0] = ll
        out[1] = hypot(aa, bb)
        out[2] = (atan2(bb, aa) * 180.0 / PI + 360.0) % 360.0
    }

    /**
     * CSS's saturate() on an sRGB colour (the filter's matrix, applied in sRGB as Chrome applies CSS
     * filters). The light's canvas carried saturate(1.15); here each dab's colour carries it instead,
     * which comes to the same since the matrix has no offsets.
     */
    fun saturate(rgb: Int, amount: Double): Int {
        val r = red(rgb).toDouble()
        val g = green(rgb).toDouble()
        val b = blue(rgb).toDouble()
        val s = amount
        val r2 = (0.213 + 0.787 * s) * r + (0.715 - 0.715 * s) * g + (0.072 - 0.072 * s) * b
        val g2 = (0.213 - 0.213 * s) * r + (0.715 + 0.285 * s) * g + (0.072 - 0.072 * s) * b
        val b2 = (0.213 - 0.213 * s) * r + (0.715 - 0.715 * s) * g + (0.072 + 0.928 * s) * b
        return rgb(byte(r2), byte(g2), byte(b2))
    }

    /** Any hue that lands on olive leans to amber: dark yellow reads as olive on black. */
    fun unOlive(hue: Double): Double {
        val y = ((hue % 360.0) + 360.0) % 360.0
        return if (y > 75.0 && y < 120.0) 68.0 else y
    }

    /**
     * How vivid a pixel is, as the prototype's cover picker scores it: HSV saturation times value, with
     * value counted fully from 71% up, so dark pixels lose even when saturated.
     */
    fun vividness(rgb: Int): Double {
        val r = red(rgb)
        val g = green(rgb)
        val b = blue(rgb)
        val max = max(r, max(g, b)) / 255.0
        val min = min(r, min(g, b)) / 255.0
        val s = if (max == 0.0) 0.0 else (max - min) / max
        return s * min(1.0, max * 1.4)
    }

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun red(c: Int) = (c shr 16) and 0xFF

    fun green(c: Int) = (c shr 8) and 0xFF

    fun blue(c: Int) = c and 0xFF

    // Linear light, clipped to the sRGB gamut, to an 8-bit sRGB channel.
    private fun encode(linear: Double): Int {
        val v = linear.coerceIn(0.0, 1.0)
        val e = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
        return (e * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun decode(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun byte(v: Double) = v.roundToInt().coerceIn(0, 255)
}
