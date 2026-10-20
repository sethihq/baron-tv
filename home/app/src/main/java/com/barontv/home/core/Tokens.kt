package com.barontv.home.core

import android.content.Context
import android.graphics.Typeface
import com.barontv.home.R

/**
 * The prototype's design tokens (prototype/app.css, `.screen`), converted from OKLCH to sRGB exactly.
 * Sizes are dp: HomeActivity lays out at 213 dpi, so the TV's 1280 x 720 is the prototype's 960 x 540.
 */
object Tokens {
    const val BG = 0xFF000000.toInt()
    const val BAND = 0x13FFFFFF // the app dock's tray: white at 7.5%
    const val FILL = 0xFF1A1A1C.toInt() // a poster with no art yet
    const val FILL_2 = 0x1AFFFFFF
    const val CHIP = 0x14FFFFFF
    const val CHIP_ON = 0x2BFFFFFF
    const val PANEL = 0xFF282828.toInt()
    const val TEXT = 0xFFEBEBEB.toInt()
    const val TEXT_2 = 0xFFA1A1A1.toInt()
    const val FOCUS_RING = 0x99FFFFFF.toInt() // 1 dp, 2 dp outside the art: fine and soft
    const val POSTER_FOCUS = 0xFF2B2B2D.toInt() // a focused poster with no art yet
    const val DOCK_FRAME = 0xFF020202.toInt() // the bento's and side dock's black frame
    const val FRAME_HAIRLINE = 0x1CFFFFFF // its hairline: white at 11%
    const val TILE = 0xFF161616.toInt() // bento and side dock tiles (oklch 20%)
    const val TILE_FOCUS = 0xFF3D3D3D.toInt() // a focused tile: colour only, no lift (oklch 36%)
    const val LIVE_GREEN = 0xFF56D57B.toInt() // HDMI 1's dot, the Wi-Fi switch
    const val CONNECTED_GREEN = 0xFF67D283.toInt() // "Connected", ticks
    const val CALENDAR_RED = 0xFFFC5855.toInt() // the weekday

    // Type scale (sp = dp here) and tracking, as the prototype's --t-* and --tr-*.
    const val T_DISPLAY = 56f
    const val T_SUB = 17f
    const val T_BODY = 15f
    const val T_LABEL = 13f
    const val T_CAPTION = 12f

    // Spacing: --s-1 to --s-8, and the safe area round the screen.
    const val S1 = 4f
    const val S2 = 8f
    const val S3 = 12f
    const val S4 = 16f
    const val S5 = 20f
    const val S6 = 24f
    const val S8 = 32f
    const val SAFE_TOP = 32f
    const val SAFE_SIDE = 56f

    /** The ease the prototype uses everywhere: cubic-bezier(0.23, 1, 0.32, 1). */
    val EASE_OUT = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)
}

/** Open Runde, the only font, in its four weights. Loaded once and shared. */
object Fonts {
    private var cache: Array<Typeface>? = null

    private fun all(context: Context): Array<Typeface> = cache ?: arrayOf(
        context.resources.getFont(R.font.open_runde_regular),
        context.resources.getFont(R.font.open_runde_medium),
        context.resources.getFont(R.font.open_runde_semibold),
        context.resources.getFont(R.font.open_runde_bold),
    ).also { cache = it }

    fun regular(context: Context) = all(context)[0]
    fun medium(context: Context) = all(context)[1]
    fun semibold(context: Context) = all(context)[2]
    fun bold(context: Context) = all(context)[3]
}

/** dp to px for this context's density. */
fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dpi(value: Float): Int = Math.round(dp(value))
