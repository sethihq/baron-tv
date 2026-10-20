package com.barontv.remote.ui.playing

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.barontv.remote.R
import com.barontv.remote.ui.Palette
import kotlin.math.min

/**
 * How an app looks on the Playing tab. The TV can't send cover art, so the app stands in for the album:
 * Spotify by its own cover (the one the TV's dock and the Apps tab use), any other app by a calm tile in
 * its colour. [vivid] is what the glow behind the screen is made from, as the prototype takes a cover's
 * most vivid colour.
 *
 * The Spotify cover here is `playing_cover_spotify`, the same SVG rendered at three times the size of
 * `cover_spotify` (960 by 540): the Playing tab shows it 312 dp wide, where the 320 pixel one goes soft.
 */
internal class AppLook(
    @DrawableRes val cover: Int?,
    /** The calm tile's fill, when there's no [cover]. */
    val tile: Color,
    val vivid: Color,
    /** The small mark before "Spotify on Baron TV". */
    @DrawableRes val mark: Int,
    val markTint: Color,
)

/**
 * The look for [packageName]. The apps this TV has get their own brand colour, taken from their covers in
 * `prototype/assets/apps/covers/`; anything else gets a steady colour of its own from its name, so the
 * same app always looks the same.
 */
internal fun lookFor(packageName: String): AppLook {
    if (packageName == SPOTIFY) {
        return AppLook(
            cover = R.drawable.playing_cover_spotify,
            tile = Palette.Fill,
            // The green of Spotify's mark, the most vivid colour on its cover.
            vivid = Color(0xFF1ED760),
            mark = R.drawable.ph_spotify_logo_fill,
            markTint = Palette.Spotify,
        )
    }
    val brand = BrandColours[packageName] ?: colourFromName(packageName)
    val (_, c, h) = brand.toOklch()
    // Deep and quiet: the tile sits on black next to white text, so it only hints at the brand.
    val tile = oklch(0.32f, min(c, 0.075f), amber(h))
    return AppLook(
        cover = null,
        tile = tile,
        vivid = tile,
        mark = R.drawable.ph_music_notes_fill,
        markTint = oklch(0.8f, min(c, 0.11f), amber(h)),
    )
}

/** The colour of each app on this TV, from its cover (the cover's background, or its mark on a dark one). */
private val BrandColours = mapOf(
    "com.google.android.youtube.tv" to Color(0xFFFF0000),
    "com.amazon.amazonvideo.livingroom" to Color(0xFF1A97FF),
    // JioHotstar's cover runs blue to violet to pink; this is the violet at its middle.
    "in.startv.hotstar" to Color(0xFF6A3EFB),
    "tv.cloudwalker.market" to Color(0xFFFFB000),
    "com.cvte.tv.media" to Color(0xFF16BED3),
    "com.netflix.mediaclient" to Color(0xFFE50914),
)

/** A hue from the package name, so an app the TV hasn't been told about still keeps one colour. */
private fun colourFromName(packageName: String): Color {
    val hue = ((packageName.hashCode() % 360) + 360) % 360
    return oklch(0.62f, 0.12f, hue.toFloat())
}

/** Yellow leaned to amber, as the glow does it: a dark yellow on black reads as olive. */
private fun amber(h: Float) = if (h > 75f && h < 120f) 68f else h
