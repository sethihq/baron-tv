package com.barontv.home.side

import com.barontv.home.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiMathTest {

    @Test
    fun levelsMatchAndroidNinesFourLevelScale() {
        // calculateSignalLevel(rssi, 4) on Android 9: -100 and below 0, -55 and above 3, linear between.
        assertEquals(0, WifiMath.level(-110))
        assertEquals(0, WifiMath.level(-100))
        assertEquals(0, WifiMath.level(-86))
        assertEquals(1, WifiMath.level(-85))
        assertEquals(1, WifiMath.level(-71))
        assertEquals(2, WifiMath.level(-70))
        assertEquals(2, WifiMath.level(-56))
        assertEquals(3, WifiMath.level(-55))
        assertEquals(3, WifiMath.level(-30))
    }

    @Test
    fun strengthPicksTheFansArcs() {
        assertEquals(R.drawable.ph_wifi_high, WifiMath.glyph(-50))
        assertEquals(R.drawable.ph_wifi_medium, WifiMath.glyph(-65))
        assertEquals(R.drawable.ph_wifi_low, WifiMath.glyph(-80))
        assertEquals(R.drawable.ph_wifi_low, WifiMath.glyph(-95))
    }

    @Test
    fun locksAreAnyPasswordedSecurity() {
        assertTrue(WifiMath.locked("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertTrue(WifiMath.locked("[WEP][ESS]"))
        assertTrue(WifiMath.locked("[WPA2-EAP-CCMP][ESS]"))
        assertTrue(WifiMath.locked("[RSN-SAE-CCMP][ESS]"))
        assertFalse(WifiMath.locked("[ESS]"))
        assertFalse(WifiMath.locked(null))
    }

    @Test
    fun nearbyKeepsTheStrongestOfEachNameStrongestFirst() {
        val scan = listOf(
            Net("Home 2.4G", -60, true),
            Net("Cafe Guest", -82, false),
            Net("JioFiber-8812", -66, true),
            Net("Home 2.4G", -48, true),
            Net("", -40, false),
            Net("Home 5G", -45, true),
            Net("JioFiber-8812", -70, true),
        )
        val list = WifiMath.nearby(scan, exclude = "Home 5G")
        assertEquals(listOf("Home 2.4G", "JioFiber-8812", "Cafe Guest"), list.map { it.ssid })
        assertEquals(-48, list[0].rssi)
        assertEquals(-66, list[1].rssi)
    }

    @Test
    fun nearbyBreaksTiesByName() {
        val list = WifiMath.nearby(listOf(Net("b", -60, true), Net("a", -60, true)), exclude = null)
        assertEquals(listOf("a", "b"), list.map { it.ssid })
    }

    @Test
    fun ssidsAreUnquotedAndHiddenOnesAreNull() {
        assertEquals("Home 5G", WifiMath.clean("\"Home 5G\""))
        assertEquals("Home 5G", WifiMath.clean("Home 5G"))
        assertNull(WifiMath.clean("<unknown ssid>"))
        assertNull(WifiMath.clean("0x"))
        assertNull(WifiMath.clean(""))
        assertNull(WifiMath.clean(null))
        assertEquals("\"Home 5G\"", WifiMath.quoted("Home 5G"))
    }
}
