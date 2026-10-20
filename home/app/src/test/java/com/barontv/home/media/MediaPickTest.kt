package com.barontv.home.media

import com.barontv.home.media.MediaPick.STATE_BUFFERING
import com.barontv.home.media.MediaPick.STATE_ERROR
import com.barontv.home.media.MediaPick.STATE_NONE
import com.barontv.home.media.MediaPick.STATE_PAUSED
import com.barontv.home.media.MediaPick.STATE_PLAYING
import com.barontv.home.media.MediaPick.STATE_SKIPPING_TO_NEXT
import com.barontv.home.media.MediaPick.STATE_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPickTest {

    @Test
    fun thePlayingSessionWinsWhereverItIs() {
        assertEquals(2, MediaPick.pick(listOf(STATE_PAUSED, STATE_STOPPED, STATE_PLAYING)))
        assertEquals(0, MediaPick.pick(listOf(STATE_PLAYING, STATE_PLAYING)))
    }

    @Test
    fun elseTheMostRecentPausedOne() {
        assertEquals(0, MediaPick.pick(listOf(STATE_PAUSED, STATE_PAUSED)))
        assertEquals(1, MediaPick.pick(listOf(STATE_STOPPED, STATE_PAUSED)))
    }

    @Test
    fun nothingToShowIsNoPick() {
        assertNull(MediaPick.pick(emptyList()))
        assertNull(MediaPick.pick(listOf(STATE_STOPPED, STATE_NONE, STATE_ERROR)))
    }

    @Test
    fun bufferingAndSkippingCountAsPlaying() {
        assertTrue(MediaPick.playing(STATE_BUFFERING))
        assertTrue(MediaPick.playing(STATE_SKIPPING_TO_NEXT))
        assertEquals(1, MediaPick.pick(listOf(STATE_PAUSED, STATE_BUFFERING)))
        assertFalse(MediaPick.playing(STATE_PAUSED))
        assertTrue(MediaPick.shows(STATE_PAUSED))
        assertFalse(MediaPick.shows(STATE_STOPPED))
    }

    @Test
    fun spotifyIsAlwaysMusicOthersByWhatTheyPlay() {
        assertTrue(MediaPick.isMusic("com.spotify.tv.android", emptyList()))
        assertTrue(MediaPick.isMusic("com.google.android.youtube.tv", listOf(3, MediaPick.CONTENT_TYPE_MUSIC)))
        // CONTENT_TYPE_MOVIE (3) is not music: a film's soundtrack stays dark.
        assertFalse(MediaPick.isMusic("com.google.android.youtube.tv", listOf(3)))
        assertFalse(MediaPick.isMusic("com.amazon.amazonvideo.livingroom", emptyList()))
    }

    @Test
    fun artFitsIn256KeepingItsShape() {
        assertEquals(256 to 256, MediaPick.fit(640, 640))
        assertEquals(256 to 144, MediaPick.fit(1280, 720))
        assertEquals(144 to 256, MediaPick.fit(720, 1280))
        assertEquals(200 to 100, MediaPick.fit(200, 100))
        assertEquals(0 to 0, MediaPick.fit(0, 100))
    }
}
