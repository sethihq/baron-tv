package com.barontv.remote.ui.playing

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.barontv.remote.R
import com.barontv.remote.tv.NowPlaying
import com.barontv.remote.tv.TV_NAME
import com.barontv.remote.tv.Volume
import com.barontv.remote.ui.Glyph
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.Space
import com.barontv.remote.ui.Type

/**
 * For previews and snapshots only: the Playing tab where the app shell puts it, under a 64 dp header and
 * above a 68 dp tab bar, both drawn here as plain stand-ins after the prototype. The real header and tab
 * bar belong to the shell.
 */
@Composable
internal fun ShellStandIn(content: @Composable (Modifier) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(TV_NAME, style = Type.Headline)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Palette.Live, CircleShape))
                Spacer(Modifier.width(6.dp))
                BasicText("Connected", style = Type.Caption.copy(color = Palette.Text2))
            }
        }
        content(Modifier.weight(1f).fillMaxWidth())
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(start = Space.S2, end = Space.S2, top = Space.S2, bottom = Space.S1),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            StandInTab(R.drawable.ph_arrows_out_cardinal, "Remote", selected = false)
            StandInTab(R.drawable.ph_squares_four, "Apps", selected = false)
            StandInTab(R.drawable.ph_music_notes_fill, "Playing", selected = true)
            StandInTab(R.drawable.ph_television, "TV", selected = false)
        }
    }
}

@Composable
private fun StandInTab(@DrawableRes icon: Int, label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S1)) {
        Box(
            Modifier
                .size(60.dp, 32.dp)
                .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(icon, 22.dp, if (selected) Palette.Accent else Palette.Text2)
        }
        BasicText(
            label,
            style = Type.Caption.copy(color = if (selected) Palette.Text else Palette.Text2, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** The tab showing [ui], answered by [bridge], inside the stand-in shell. The first bridge is kept. */
@Composable
internal fun PlayingPreview(ui: PlayingUi, bridge: PlayingFakeBridge, previewHeld: Boolean = false) {
    val model = remember { PlayingModel(bridge, initial = ui) }
    ShellStandIn { place -> PlayingContent(model, place, previewHeld) }
}

/** What the tab shows for [now] at the fake's level, as the model would once the TV has answered. */
internal fun playing(now: NowPlaying, level: Int = 40) = PlayingUi.Playing(now, Volume(level, muted = false))

@Preview(name = "Song", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun SongPreview() = PlayingPreview(playing(PlayingSamples.Song), PlayingFakeBridge(PlayingSamples.Song))

@Preview(name = "Spotify, no song read", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun UnreadPreview() =
    PlayingPreview(playing(PlayingSamples.SpotifyUnread), PlayingFakeBridge(PlayingSamples.SpotifyUnread))

@Preview(name = "Paused", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun PausedPreview() {
    val paused = PlayingSamples.Song.copy(playing = false)
    PlayingPreview(playing(paused), PlayingFakeBridge(paused))
}

@Preview(name = "Another app", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun OtherAppPreview() = PlayingPreview(playing(PlayingSamples.YouTube), PlayingFakeBridge(PlayingSamples.YouTube))

@Preview(name = "Nothing playing", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun NothingPreview() = PlayingPreview(PlayingUi.Idle, PlayingFakeBridge(now = null))

@Preview(name = "No bridge", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun NoBridgePreview() = PlayingPreview(PlayingUi.NoBridge, PlayingFakeBridge(available = false))

@Preview(name = "Small phone", device = Devices.NEXUS_5, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun SmallPhonePreview() = PlayingPreview(playing(PlayingSamples.Song), PlayingFakeBridge(PlayingSamples.Song))
