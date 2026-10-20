package com.barontv.remote.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.barontv.remote.R

// The design tokens of the approved phone prototype (prototype/phone/phone.css), ported one to one.
// One CSS pixel there is one dp here. The prototype writes its colours in OKLCH; each one below was
// converted to sRGB with the CSS Color 4 maths and checked against what Chrome paints, not matched by eye.

/** Black, with trays as a veil of white, and one accent. Dark only, as on the TV. */
object Palette {
    /** oklch(0% 0 none): the screen. */
    val Bg = Color(0xFF000000)

    /** oklch(100% 0 none / 0.07): trays, the touch pad and the round keys sit on this veil. */
    val Veil = Color.White.copy(alpha = 0.07f)

    /** oklch(100% 0 none / 0.13): a veil pressed. */
    val Veil2 = Color.White.copy(alpha = 0.13f)

    /** oklch(100% 0 none / 0.2): tracks. */
    val Veil3 = Color.White.copy(alpha = 0.2f)

    /** oklch(100% 0 none / 0.09): the touch pad while a finger is on it, a shade up from [Veil]. */
    val PadPressed = Color.White.copy(alpha = 0.09f)

    /** oklch(100% 0 none / 0.08): hairlines between rows. */
    val Line = Color.White.copy(alpha = 0.08f)

    /** oklch(21.8% 0.004 286): behind artwork while it loads. */
    val Fill = Color(0xFF1A1A1C)

    /** oklch(27.5% 0 none): pills that float over the screen. */
    val Panel = Color(0xFF282828)

    /** oklch(95% 0 none): text and icons. */
    val Text = Color(0xFFEEEEEE)

    /** oklch(71% 0 none): quieter text, such as the labels under the keys. */
    val Text2 = Color(0xFFA1A1A1)

    /** oklch(55% 0 none): the quietest text, such as the touch pad's hint. */
    val Text3 = Color(0xFF717171)

    /** oklch(78% 0.17 150): the "Connected" dot. */
    val Live = Color(0xFF56D57B)

    /** oklch(74% 0.19 150): Spotify's green, for the Now playing tab. */
    val Spotify = Color(0xFF2DCB65)

    /** oklch(73% 0.14 243): the one accent, sky blue. */
    val Accent = Color(0xFF4BB0F8)

    /** Keyboard focus: the accent at 60%, so a ring marks the spot without glowing. */
    val Ring = Accent.copy(alpha = 0.6f)
}

/** Open Runde, the design's only font. It has no Light, so nothing here goes below Regular. */
val OpenRunde = FontFamily(
    Font(R.font.open_runde_regular, FontWeight.Normal),
    Font(R.font.open_runde_medium, FontWeight.Medium),
    Font(R.font.open_runde_semibold, FontWeight.SemiBold),
    Font(R.font.open_runde_bold, FontWeight.Bold),
)

/**
 * Five sizes, each with its weight, leading and tracking, as the prototype's `--t-*` tokens. Tracking
 * tightens as the size grows and opens a touch below 13.
 */
object Type {
    val Large = style(FontWeight.Bold, size = 28, leading = 34, trackingEm = -0.022f)
    val Title = style(FontWeight.SemiBold, size = 22, leading = 28, trackingEm = -0.017f)
    val Headline = style(FontWeight.SemiBold, size = 17, leading = 22, trackingEm = -0.01f)
    val Body = style(FontWeight.Medium, size = 15, leading = 20, trackingEm = 0f)
    val Caption = style(FontWeight.Medium, size = 12, leading = 16, trackingEm = 0.01f)

    private fun style(weight: FontWeight, size: Int, leading: Int, trackingEm: Float) = TextStyle(
        color = Palette.Text,
        fontFamily = OpenRunde,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = leading.sp,
        letterSpacing = trackingEm.em,
        // CSS centres the glyphs in the line and keeps the leading above the first line and below the
        // last, so the boxes measure what the prototype's do.
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
}

/** Spacing on a 4 and 8 grid, as `--s-1` to `--s-10`. */
object Space {
    val S1: Dp = 4.dp
    val S2: Dp = 8.dp
    val S3: Dp = 12.dp
    val S4: Dp = 16.dp
    val S5: Dp = 20.dp
    val S6: Dp = 24.dp
    val S7: Dp = 28.dp
    val S8: Dp = 32.dp
    val S10: Dp = 40.dp

    /** The screen's side margin. */
    val Edge: Dp = S5
}

object Motion {
    /** cubic-bezier(0.23, 1, 0.32, 1): a quick start and a long, soft settle. Presses and fades use it. */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
}
