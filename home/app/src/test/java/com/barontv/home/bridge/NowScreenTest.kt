package com.barontv.home.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What's playing, read off a screen's accessibility tree. The fixtures in test/resources/bridge are in
 * `uiautomator dump`'s format, made by hand after Spotify's TV app (the TV was off when this was written).
 * To check the heuristics against the real thing, with Spotify playing in front on the TV:
 *   TV_HOST=<ip> tools/venv/bin/python -I tools/tv.py "uiautomator dump /data/local/tmp/ui.xml && cat /data/local/tmp/ui.xml"
 * save the XML next to these as spotify-real.xml and add a test like spotifysNowPlayingView's.
 */
class NowScreenTest {

    private fun text(left: Int, top: Int, right: Int, bottom: Int, text: String = "", desc: String = "", cls: String = "android.widget.TextView") =
        ScreenText(left, top, right, bottom, text = text, desc = desc, cls = cls)

    private fun button(left: Int, top: Int, desc: String) = text(left, top, left + 60, top + 60, desc = desc, cls = "android.widget.Button")

    /** A player's bottom half: the progress row and the transport, as in the Spotify fixture. */
    private fun player(position: String = "1:02", length: String = "2:55") = listOf(
        text(424, 520, 470, 540, position),
        text(1170, 520, 1216, 540, length),
        button(700, 580, "Previous"),
        button(780, 580, "Pause"),
        button(860, 580, "Next"),
    )

    @Test
    fun spotifysNowPlayingView() {
        val track = NowScreen.read(UiDump.fixture("spotify-npv.xml"), TV)
        assertEquals(ScreenTrack("Espresso", "Sabrina Carpenter", 62_000, 175_000), track)
    }

    @Test
    fun pausedWithTheTimeLeftAndTwoArtists() {
        val track = NowScreen.read(UiDump.fixture("spotify-npv-paused.xml"), TV)
        assertEquals(ScreenTrack("Starboy", "The Weeknd, Daft Punk", 117_000, 230_000), track)
    }

    @Test
    fun browsingIsNotPlaying() {
        assertEquals(ScreenTrack.NONE, NowScreen.read(UiDump.fixture("spotify-home.xml"), TV))
    }

    @Test
    fun aPlaylistsPlayButtonAndSongLengthsAreNotAPlayer() {
        assertEquals(ScreenTrack.NONE, NowScreen.read(UiDump.fixture("spotify-playlist.xml"), TV))
    }

    @Test
    fun aNativePlayerByItsIds() {
        val track = NowScreen.read(UiDump.fixture("native-player.xml"), TV)
        assertEquals(ScreenTrack("Bohemian Rhapsody", "Queen", 62_000, 355_000), track)
    }

    @Test
    fun nowPlayingInWords() {
        val nodes = listOf(
            text(64, 600, 600, 660, desc = "Now playing: Espresso by Sabrina Carpenter", cls = "android.view.View"),
            text(64, 100, 400, 140, "Recently played"),
        )
        assertEquals(ScreenTrack("Espresso", "Sabrina Carpenter"), NowScreen.read(nodes, TV))
    }

    @Test
    fun bothTimesInOneLabel() {
        val nodes = listOf(
            text(424, 388, 640, 436, "Espresso"),
            text(424, 444, 660, 474, "Sabrina Carpenter"),
            text(424, 520, 560, 540, "1:02 / 2:55"),
        )
        assertEquals(ScreenTrack("Espresso", "Sabrina Carpenter", 62_000, 175_000), NowScreen.read(nodes, TV))
    }

    @Test
    fun anHourLongEpisode() {
        val nodes = listOf(text(424, 388, 900, 436, "Episode 212: Slow Mornings")) + player("1:02:03", "1:30:00")
        assertEquals(ScreenTrack("Episode 212: Slow Mornings", "", 3_723_000, 5_400_000), NowScreen.read(nodes, TV))
    }

    @Test
    fun songAndArtistOnOneLine() {
        val nodes = listOf(text(424, 440, 900, 474, "Espresso \u2022 Sabrina Carpenter")) + player()
        assertEquals(ScreenTrack("Espresso", "Sabrina Carpenter", 62_000, 175_000), NowScreen.read(nodes, TV))
    }

    @Test
    fun aSongMayBeCalledWhatAButtonSays() {
        val nodes = listOf(
            text(424, 388, 640, 436, "Home"),
            text(424, 444, 660, 474, "Michael Bubl\u00E9"),
        ) + player()
        assertEquals(ScreenTrack("Home", "Michael Bubl\u00E9", 62_000, 175_000), NowScreen.read(nodes, TV))
    }

    @Test
    fun theTitleIsTheTallestOfTheNearestLines() {
        // Album under the artist: the biggest line is still the title, the one under it the artist.
        val nodes = listOf(
            text(424, 330, 800, 378, "Bohemian Rhapsody"),
            text(424, 386, 700, 412, "Queen"),
            text(424, 420, 700, 444, "A Night at the Opera"),
        ) + player()
        assertEquals("Bohemian Rhapsody", NowScreen.read(nodes, TV).title)
        assertEquals("Queen", NowScreen.read(nodes, TV).artist)
    }

    @Test
    fun linesFarAboveThePlayerArentTheSong() {
        // Only a heading at the very top: the times are read, the song isn't guessed.
        val nodes = listOf(text(424, 10, 900, 40, "Today's Top Hits")) + player()
        assertEquals(ScreenTrack(positionMs = 62_000, durationMs = 175_000), NowScreen.read(nodes, TV))
    }

    @Test
    fun linesBesideThePlayerArentTheSong() {
        // To the left of everything the player spans: a side menu, not the song.
        val nodes = listOf(text(16, 440, 200, 474, "Your Library")) + player()
        assertEquals("", NowScreen.read(nodes, TV).title)
    }

    @Test
    fun playOrPauseNeedsNextOrPrevious() {
        val nodes = listOf(text(180, 120, 520, 180, "Espresso"), button(180, 240, "Play"), button(300, 240, "Shuffle"))
        assertEquals(ScreenTrack.NONE, NowScreen.read(nodes, TV))
    }

    @Test
    fun timesCloseTogetherArentAProgressRow() {
        val nodes = listOf(text(424, 388, 640, 436, "Espresso"), text(424, 520, 470, 540, "1:02"), text(480, 520, 526, 540, "2:55"))
        assertEquals(ScreenTrack.NONE, NowScreen.read(nodes, TV))
    }

    @Test
    fun theNodesUsedAreReported() {
        val nodes = UiDump.fixture("spotify-npv.xml")
        val pick = NowScreen.pick(nodes, TV)
        assertEquals(setOf("Espresso", "Sabrina Carpenter", "1:02", "2:55"), pick.used.map { nodes[it].text }.toSet())
    }

    @Test
    fun aBannersPlayDoesntJoinAMiniPlayersButtons() {
        // Browsing, with a featured song's Play up top and a mini player along the bottom whose song sits
        // beside its buttons, not above them: the cards above the buttons are no song, so nothing is said.
        val nodes = listOf(
            text(180, 120, 520, 180, "Espresso"),
            button(180, 240, "Play"),
            text(560, 500, 760, 524, "Today's Top Hits"),
            text(100, 640, 400, 664, "Starboy"),
            text(100, 666, 400, 686, "The Weeknd"),
            button(560, 640, "Previous"),
            button(630, 640, "Pause"),
            button(700, 640, "Next"),
        )
        assertEquals(ScreenTrack.NONE, NowScreen.read(nodes, TV))
    }

    @Test
    fun withOnlyButtonsTheSongMustSitRightAboveThem() {
        val near = listOf(text(560, 560, 760, 600, "Starboy"), button(560, 640, "Previous"), button(630, 640, "Pause"), button(700, 640, "Next"))
        assertEquals("Starboy", NowScreen.read(near, TV).title)
        val far = listOf(text(560, 420, 760, 460, "Starboy")) + near.drop(1)
        assertEquals("", NowScreen.read(far, TV).title)
    }

    @Test
    fun timesInMilliseconds() {
        assertEquals(62_000L, NowScreen.ms("1:02"))
        assertEquals(113_000L, NowScreen.ms("-1:53"))
        assertEquals(113_000L, NowScreen.ms("\u22121:53"))
        assertEquals(3_723_000L, NowScreen.ms("1:02:03"))
        assertEquals(-1L, NowScreen.ms("1:xx"))
    }

    private companion object {
        /** The fixtures' screen: the TV's 1280 x 720. */
        const val TV = 720
    }
}
