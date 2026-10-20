package com.barontv.home.volume

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeStepsTest {

    @Test
    fun stepsAreFivesOnTheGrid() {
        assertEquals(40, VolumeSteps.stepPercent(35, up = true))
        assertEquals(30, VolumeSteps.stepPercent(35, up = false))
        // Off the grid (the phone's slider, the TV's own keys): to the next five that way.
        assertEquals(40, VolumeSteps.stepPercent(37, up = true))
        assertEquals(35, VolumeSteps.stepPercent(37, up = false))
        assertEquals(5, VolumeSteps.stepPercent(1, up = true))
        assertEquals(0, VolumeSteps.stepPercent(4, up = false))
    }

    @Test
    fun stepsStopAt0And100() {
        assertEquals(100, VolumeSteps.stepPercent(100, up = true))
        assertEquals(100, VolumeSteps.stepPercent(98, up = true))
        assertEquals(0, VolumeSteps.stepPercent(0, up = false))
        assertEquals(0, VolumeSteps.stepPercent(-20, up = false))
        assertEquals(100, VolumeSteps.stepPercent(140, up = true))
    }

    @Test
    fun onThisTvAnIndexIsAPercent() {
        assertEquals(40, VolumeSteps.stepIndex(35, 100, up = true))
        assertEquals(30, VolumeSteps.stepIndex(35, 100, up = false))
        assertEquals(100, VolumeSteps.stepIndex(100, 100, up = true))
        assertEquals(0, VolumeSteps.stepIndex(0, 100, up = false))
        assertEquals(35, VolumeSteps.percent(35, 100))
        assertEquals(35, VolumeSteps.index(35, 100))
    }

    @Test
    fun aCoarseStreamStillMovesEveryStep() {
        // Android's usual music range is 0 to 15: 5% is a third of a step, yet each press moves it.
        assertEquals(6, VolumeSteps.stepIndex(5, 15, up = true))
        assertEquals(7, VolumeSteps.stepIndex(6, 15, up = true))
        assertEquals(4, VolumeSteps.stepIndex(5, 15, up = false))
        assertEquals(15, VolumeSteps.stepIndex(15, 15, up = true))
        assertEquals(0, VolumeSteps.stepIndex(0, 15, up = false))
    }

    @Test
    fun percentAndIndexStayInRange() {
        assertEquals(47, VolumeSteps.percent(7, 15))
        assertEquals(0, VolumeSteps.percent(5, 0))
        assertEquals(100, VolumeSteps.index(250, 100))
        assertEquals(0, VolumeSteps.index(-5, 100))
        assertEquals(8, VolumeSteps.index(50, 15))
        assertEquals(0, VolumeSteps.stepIndex(3, 0, up = true))
    }

    @Test
    fun theVolumeKeys() {
        assertTrue(VolumeKeys.isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(VolumeKeys.isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(VolumeKeys.isVolumeKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertFalse(VolumeKeys.isVolumeKey(KeyEvent.KEYCODE_TV_INPUT))
        assertNull(VolumeKeys.action(KeyEvent.KEYCODE_DPAD_UP, down = true, repeatCount = 0))
    }

    @Test
    fun heldUpAndDownStepWithEveryRepeat() {
        assertEquals(VolumeKey.UP, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_UP, down = true, repeatCount = 0))
        assertEquals(VolumeKey.UP, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_UP, down = true, repeatCount = 7))
        assertEquals(VolumeKey.DOWN, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_DOWN, down = true, repeatCount = 3))
        // The release is still taken, so the TV's own handling never sees half a press.
        assertEquals(VolumeKey.NOTHING, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_UP, down = false, repeatCount = 0))
    }

    @Test
    fun muteTogglesOncePerPress() {
        assertEquals(VolumeKey.MUTE, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_MUTE, down = true, repeatCount = 0))
        assertEquals(VolumeKey.NOTHING, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_MUTE, down = true, repeatCount = 1))
        assertEquals(VolumeKey.NOTHING, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_MUTE, down = false, repeatCount = 0))
    }

    @Test
    fun theBarSitsWhereTheSideDockDoes() {
        // The TV's 1280 x 720: the side dock's 232 x 371.6 dp at 672, 32, at 4/3 px per dp.
        assertEquals(VolumePlace.Box(896, 43, 1205, 538), VolumePlace.box(1280, 720))
        assertEquals(19, VolumePlace.slide(1280))
        // The window reaches past the box by the slide, still inside the screen.
        assertTrue(VolumePlace.box(1280, 720).right + VolumePlace.slide(1280) <= 1280)
        assertEquals(VolumePlace.Box(1344, 64, 1808, 807), VolumePlace.box(1920, 1080))
    }
}
