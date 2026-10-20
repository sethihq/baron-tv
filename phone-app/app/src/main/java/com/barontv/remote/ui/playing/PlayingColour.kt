package com.barontv.remote.ui.playing

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The prototype writes its colours in OKLCH and builds the Playing glow from the cover's colour in OKLCH
// too (phone.js, toOklch and the npArt load handler). These are the CSS Color 4 conversions it relies on
// (Björn Ottosson's OKLab matrices, the same numbers phone.js uses), so the glow comes out as Chrome
// paints it rather than matched by eye.

/** A colour in OKLCH: lightness 0 to 1, chroma, and hue in degrees. */
internal data class Oklch(val l: Float, val c: Float, val h: Float)

internal fun Color.toOklch(): Oklch {
    val (l, a, b) = toOklab()
    val hue = (atan2(b.toDouble(), a.toDouble()) * 180.0 / PI + 360.0) % 360.0
    return Oklch(l, hypot(a, b), hue.toFloat())
}

/** An OKLCH colour in sRGB. Anything outside sRGB is clipped channel by channel, as Chrome draws it. */
internal fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
    val rad = h * PI / 180.0
    return oklab(l, (c * cos(rad)).toFloat(), (c * sin(rad)).toFloat(), alpha)
}

/**
 * Between [from] and [to] at [t], mixed in OKLab with premultiplied alpha, which is how Chrome blends the
 * stops of a gradient written in oklch(). A gradient on the phone blends in sRGB instead, so the glow
 * gets a few of these in between its stops to keep the same midtones.
 */
internal fun mixOklab(from: Color, to: Color, t: Float): Color {
    val alpha = from.alpha + (to.alpha - from.alpha) * t
    if (alpha <= 0f) return to.copy(alpha = 0f)
    val a = from.toOklab()
    val b = to.toOklab()
    fun channel(i: Int) = (a[i] * from.alpha * (1 - t) + b[i] * to.alpha * t) / alpha
    return oklab(channel(0), channel(1), channel(2), alpha)
}

/** The glow's two colours: [inner] at its heart, [outer] where it thins out. */
internal data class GlowColours(val inner: Color, val outer: Color)

/**
 * The prototype's glow from a cover's most vivid colour: its hue at one lightness, with the chroma capped,
 * so no cover is louder than another. Yellows lean to amber, since dark yellow on black reads as olive,
 * and a colour with almost no chroma glows grey rather than in a hue it barely has.
 */
internal fun glowFor(vivid: Color): GlowColours {
    val (_, c, h0) = vivid.toOklch()
    val h = if (h0 > 75f && h0 < 120f) 68f else h0
    val chroma = if (c < 0.03f) 0f else min(c, 0.16f)
    return GlowColours(
        inner = oklch(0.54f, chroma, h, alpha = 0.4f),
        outer = oklch(0.44f, chroma * 0.9f, h, alpha = 0.15f),
    )
}

private fun Color.toOklab(): FloatArray {
    val r = linear(red)
    val g = linear(green)
    val b = linear(blue)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return floatArrayOf(
        (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat(),
        (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat(),
        (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat(),
    )
}

private fun oklab(l: Float, a: Float, b: Float, alpha: Float): Color {
    val lc = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val mc = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val sc = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return Color(
        red = encode(4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc),
        green = encode(-1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc),
        blue = encode(-0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/** sRGB's transfer curve, undone: the light a channel stands for. */
private fun linear(channel: Float): Double {
    val v = channel.toDouble()
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** Light back into an sRGB channel, clipped to what the screen can show. */
private fun encode(light: Double): Float {
    val v = light.coerceIn(0.0, 1.0)
    return (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).toFloat()
}
