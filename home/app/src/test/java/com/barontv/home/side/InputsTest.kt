package com.barontv.home.side

import com.barontv.home.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputsTest {

    @Test
    fun hwNumberIsTheDigitsAfterHw() {
        assertEquals(4, Inputs.hwNumber("com.mediatek.tvinput/.hdmi.HDMIInputService/HW4"))
        assertEquals(5, Inputs.hwNumber("com.mediatek.tvinput/.hdmi.HDMIInputService/HW5"))
        assertEquals(3, Inputs.hwNumber("com.mediatek.tvinput/.composite.CompositeInputService/HW3"))
        assertEquals(12, Inputs.hwNumber("x/HW12"))
    }

    @Test
    fun hwNumberNeedsTheHwSuffix() {
        assertNull(Inputs.hwNumber("com.example.input/.SoftwareInput"))
        assertNull(Inputs.hwNumber("x/HW4/extra"))
        assertNull(Inputs.hwNumber("x/HW"))
    }

    @Test
    fun theSwitchGoesToEveryReceiverTwiceWithTheSourceId() {
        val receivers = listOf(
            "com.cvte.tv.api.impl" to "com.cvte.tv.api.tif.TifReceiver",
            "com.other" to "com.other.Receiver",
        )
        val sends = Inputs.sends("com.mediatek.tvinput/.hdmi.HDMIInputService/HW4", receivers)
        assertEquals(
            listOf(
                Inputs.Send("com.cvte.tv.api.impl", "com.cvte.tv.api.tif.TifReceiver", 4),
                Inputs.Send("com.other", "com.other.Receiver", 4),
                Inputs.Send("com.cvte.tv.api.impl", "com.cvte.tv.api.tif.TifReceiver", 4),
                Inputs.Send("com.other", "com.other.Receiver", 4),
            ),
            sends,
        )
    }

    @Test
    fun theSwitchCarriesNoSourceIdWithoutAHwNumber() {
        val sends = Inputs.sends("soft/input", listOf("p" to "c"))
        assertEquals(2, sends.size)
        assertTrue(sends.all { it.sourceId == null })
    }

    @Test
    fun theSwitchUsesTheLaunchersCvteAction() {
        assertEquals("com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL", Inputs.ACTION)
        assertEquals("com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL", Inputs.CATEGORY)
        assertEquals("sourceId", Inputs.EXTRA)
        assertEquals(2, Inputs.REPEAT)
    }

    @Test
    fun baronsDefaultsCarryItsHardwareNumbers() {
        assertEquals(listOf("HDMI 1", "HDMI 2", "AV"), Inputs.BARON.map { it.label })
        assertEquals(listOf(4, 5, 3), Inputs.BARON.map { Inputs.hwNumber(it.id) })
    }

    @Test
    fun hdmiComesFirstInPortOrderThenTheRest() {
        val list = Inputs.arrange(
            listOf(
                raw("av/HW3", "AV", hdmi = false, state = Inputs.DISCONNECTED),
                raw("hdmi/HW5", "HDMI 2", state = Inputs.DISCONNECTED),
                raw("tuner/HW1", "TV", hdmi = false, passthrough = false),
                raw("hdmi/HW4", "HDMI 1", state = Inputs.CONNECTED),
            ),
        )
        assertEquals(listOf("HDMI 1", "HDMI 2", "AV"), list.map { it.label })
    }

    @Test
    fun everyHdmiPortConnectedMeansTheStateIsMadeUp() {
        val list = Inputs.arrange(
            listOf(
                raw("hdmi/HW4", "HDMI 1", state = Inputs.CONNECTED),
                raw("hdmi/HW5", "HDMI 2", state = Inputs.CONNECTED),
                raw("av/HW3", "AV", hdmi = false, state = Inputs.CONNECTED),
            ),
        )
        assertEquals(listOf(Inputs.UNKNOWN, Inputs.UNKNOWN, Inputs.CONNECTED), list.map { it.state })
    }

    @Test
    fun labelsReadAsThePrototypeWritesThem() {
        assertEquals("HDMI 1", Inputs.label("HDMI1", true, "x"))
        assertEquals("HDMI 2", Inputs.label("hdmi-2", true, "x"))
        assertEquals("HDMI 1", Inputs.label("HDMI 1", true, "x"))
        assertEquals("HDMI 3", Inputs.label("3", true, "x"))
        assertEquals("AV", Inputs.label("Composite", false, "x"))
        assertEquals("AV", Inputs.label("AV", false, "x"))
        assertEquals("some/id", Inputs.label(null, false, "some/id"))
    }

    @Test
    fun statesPickTheDotTheDimAndTheGlyph() {
        val live = TvInput("h/HW4", "HDMI 1", true, Inputs.CONNECTED)
        val standby = TvInput("h/HW5", "HDMI 2", true, Inputs.STANDBY)
        val gone = TvInput("h/HW5", "HDMI 2", true, Inputs.DISCONNECTED)
        val av = TvInput("a/HW3", "AV", false, Inputs.DISCONNECTED)
        assertTrue(Inputs.live(live))
        assertFalse(Inputs.idle(live))
        assertFalse(Inputs.live(standby))
        assertFalse(Inputs.idle(standby))
        assertTrue(Inputs.idle(gone))
        assertEquals(R.drawable.ph_plugs_connected, Inputs.glyph(live))
        assertEquals(R.drawable.ph_plug, Inputs.glyph(standby))
        assertEquals(R.drawable.ph_television_simple, Inputs.glyph(av))
    }

    private fun raw(id: String, label: String, hdmi: Boolean = true, passthrough: Boolean = true, state: Int = Inputs.STANDBY) =
        Inputs.Raw(id, label, hdmi, passthrough, state)
}
