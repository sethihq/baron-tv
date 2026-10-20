package com.barontv.home.music

import org.junit.Assert.assertEquals
import org.junit.Test

class LampSyncTest {
    @Test
    fun colourGoesToFullStrength() {
        // The lamp's brightness comes from dimming, so the colour is sent with its top channel at 255.
        val c = LampSync.lampColour(Colour.rgb(100, 50, 25))
        assertEquals(255, Colour.red(c))
        assertEquals(128, Colour.green(c))
        assertEquals(64, Colour.blue(c))
    }

    @Test
    fun blackStaysSafe() {
        val c = LampSync.lampColour(Colour.rgb(0, 0, 0))
        assertEquals(0, Colour.red(c) + Colour.green(c) + Colour.blue(c))
    }
}
