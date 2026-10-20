package com.barontv.remote.tv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search, the regular check and the key queue, with fakes on a virtual clock: times below are
 * milliseconds after start(). The fake probe answers in 2 ms where the TV is and waits out the full
 * timeout everywhere else; the fake sender takes 10 ms a press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudWalkerRemoteTest {
    private val connected = TvState.Connected("Baron TV", TV.host)

    /** Runs [body] with a fresh [Rig], and stops the remote however the test ends. */
    private fun remoteTest(body: suspend TestScope.(Rig) -> Unit) = runTest {
        val rig = Rig(testScheduler)
        try {
            body(rig)
        } finally {
            rig.remote.stop()
            runCurrent()
        }
    }

    /** Moves the virtual clock to [ms] and runs everything due by then. */
    private fun TestScope.at(ms: Long) {
        check(ms >= testScheduler.currentTime) { "the clock only goes forward" }
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    /** Every state from now on, none skipped. */
    private fun TestScope.statesOf(remote: TvRemote): List<TvState> {
        val seen = mutableListOf<TvState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { remote.state.toList(seen) }
        return seen
    }

    /** Starts with the TV answering at its saved address, connected 2 ms in. */
    private fun TestScope.connect(rig: Rig) {
        rig.store.address = TV
        rig.probe.answering += TV.host
        rig.remote.start()
        at(10)
        assertEquals(connected, rig.remote.state.value)
    }

    @Test
    fun `when the last address answers, nothing else is tried`() = remoteTest { rig ->
        rig.store.address = TV
        rig.probe.answering += TV.host
        rig.remote.start()
        at(10)
        assertEquals(TvState.Connected("Baron TV", "192.168.1.25"), rig.remote.state.value)
        assertEquals(0, rig.browser.lookups)
        assertEquals(listOf(TV.host), rig.probe.knocks.map { it.host })
        assertEquals(emptyList<TvAddress>(), rig.store.saved) // already the saved one
    }

    @Test
    fun `a dead last address falls back to mDNS`() = remoteTest { rig ->
        rig.store.address = TvAddress("192.168.1.20") // where the TV was before this boot
        rig.browser.answers = flow {
            delay(300)
            emit(TV)
            awaitCancellation()
        }
        rig.probe.answering += TV.host
        rig.remote.start()
        at(399)
        assertEquals(TvState.Searching, rig.remote.state.value)
        assertEquals(0, rig.browser.lookups) // the last address gets its 400 ms first
        at(400)
        assertEquals(1, rig.browser.lookups)
        at(710) // mDNS answers at 700, its address is checked, 2 ms
        assertEquals(connected, rig.remote.state.value)
        assertEquals(listOf("192.168.1.20", TV.host), rig.probe.knocks.map { it.host }) // no scan
        assertFalse(rig.browser.looking) // mDNS stops once it has an answer
        assertEquals(listOf(TV), rig.store.saved)
    }

    @Test
    fun `when mDNS stays silent, the scan joins in after its head start`() = remoteTest { rig ->
        rig.store.address = TvAddress("192.168.1.20")
        rig.probe.answering += TV.host
        rig.remote.start()
        at(2_399) // 400 ms on the last address, then mDNS alone for 2 s
        assertEquals(listOf("192.168.1.20"), rig.probe.knocks.map { it.host })
        assertTrue(rig.browser.looking)
        at(2_400)
        // The scan starts next to the last address and works outwards.
        assertEquals(
            listOf("192.168.1.20", "192.168.1.19", "192.168.1.21", "192.168.1.18", "192.168.1.22"),
            rig.probe.knocks.drop(1).take(5).map { it.host },
        )
        assertTrue(rig.probe.knocks.all { it.host.startsWith("192.168.1.") && it.host != HOME.ipv4 })
        at(2_410)
        assertEquals(connected, rig.remote.state.value)
        assertFalse(rig.browser.looking) // mDNS stops once the scan has found it
        assertEquals(listOf(TV), rig.store.saved)
        // The first round of 64 found it at 2402 ms and the scan stopped there. (The TV's probe frees its
        // place just before it reports, so one more knock can start in that instant; it's cancelled at once.)
        at(5_000)
        assertTrue(rig.probe.knocks.all { it.atMs <= 2_402 })
        assertTrue(rig.probe.knocks.size <= 1 + 64 + 1)
    }

    @Test
    fun `with nothing answering, the search ends in NotFound at its limit`() = remoteTest { rig ->
        rig.remote.start()
        at(6_999)
        assertEquals(TvState.Searching, rig.remote.state.value)
        assertEquals(253, rig.probe.knocks.size) // the whole /24, once
        at(7_000)
        assertEquals(TvState.NotFound, rig.remote.state.value)
        assertFalse(rig.browser.looking)
        // And then nothing carries on in the background.
        at(60_000)
        assertEquals(253, rig.probe.knocks.size)
        assertEquals(1, rig.browser.lookups)
        assertEquals(TvState.NotFound, rig.remote.state.value)
    }

    @Test
    fun `when mDNS can't run, the scan starts at once and an empty scan ends the search early`() =
        remoteTest { rig ->
            rig.browser.answers = emptyFlow()
            rig.remote.start()
            at(0)
            assertEquals(64, rig.probe.knocks.size) // the first round, straight away
            at(1_199) // four rounds of 300 ms
            assertEquals(TvState.Searching, rig.remote.state.value)
            at(1_200)
            assertEquals(TvState.NotFound, rig.remote.state.value)
        }

    @Test
    fun `an mDNS that breaks doesn't stop the scan`() = remoteTest { rig ->
        rig.browser.answers = flow { throw IllegalStateException("mDNS fell over") }
        rig.probe.answering += TV.host
        rig.remote.start()
        at(10)
        assertEquals(connected, rig.remote.state.value)
    }

    @Test
    fun `a broken store doesn't stop the search`() = remoteTest { rig ->
        rig.store.broken = true
        rig.browser.answers = flowOf(TV)
        rig.probe.answering += TV.host
        rig.remote.start()
        at(10)
        assertEquals(connected, rig.remote.state.value)
    }

    @Test
    fun `a failed press goes back to searching`() = remoteTest { rig ->
        connect(rig)
        // The TV is switched off.
        rig.sender.reachable = false
        rig.probe.answering.clear()
        rig.remote.send(TvKey.OK)
        at(20) // the press fails after its 10 ms
        assertEquals(TvState.Searching, rig.remote.state.value)
        at(20 + 7_000)
        assertEquals(TvState.NotFound, rig.remote.state.value)
    }

    @Test
    fun `the regular check keeps a TV that answers`() = remoteTest { rig ->
        connect(rig)
        at(60_000)
        assertEquals(connected, rig.remote.state.value)
        assertEquals(1 + 11, rig.probe.knocks.size) // the first knock, then one every 5 s
    }

    @Test
    fun `a TV that stops answering the regular check is looked for again`() = remoteTest { rig ->
        connect(rig)
        rig.probe.answering.clear()
        // The check at 5002 ms gets two tries of 1 s before giving up.
        at(7_001)
        assertEquals(connected, rig.remote.state.value)
        at(7_002)
        assertEquals(TvState.Searching, rig.remote.state.value)
        at(7_002 + 7_000)
        assertEquals(TvState.NotFound, rig.remote.state.value)
    }

    @Test
    fun `presses reach the TV in the order they were made`() = remoteTest { rig ->
        connect(rig)
        val keys = listOf(TvKey.UP, TvKey.UP, TvKey.RIGHT, TvKey.OK, TvKey.BACK, TvKey.VOLUME_UP, TvKey.MUTE)
        keys.forEach(rig.remote::send)
        at(1_000)
        assertEquals(keys.map { it.code }, rig.sender.delivered)
    }

    @Test
    fun `a full queue drops the newest presses instead of waiting`() = remoteTest { rig ->
        connect(rig)
        rig.sender.latencyMs = 100
        val keys = List(40) { TvKey.entries[it % TvKey.entries.size] }
        keys.forEach(rig.remote::send) // all at once, faster than they can go
        at(10_000)
        // One press went straight to the sender and 32 waited in the queue; the last 7 were dropped.
        assertEquals(keys.take(33).map { it.code }, rig.sender.delivered)
    }

    @Test
    fun `presses made while not connected are dropped, not saved up`() = remoteTest { rig ->
        rig.store.address = TV
        rig.probe.answering += TV.host
        rig.remote.send(TvKey.OK) // before start()
        rig.remote.start()
        rig.remote.send(TvKey.UP) // still searching
        at(10)
        assertEquals(connected, rig.remote.state.value)
        at(100)
        assertEquals(emptyList<Int>(), rig.sender.delivered)
    }

    @Test
    fun `no Wi-Fi says NoWifi, and Wi-Fi coming back starts a search`() = remoteTest { rig ->
        rig.wifi.link.value = null
        rig.store.address = TV
        rig.probe.answering += TV.host
        rig.remote.start()
        at(10)
        assertEquals(TvState.NoWifi, rig.remote.state.value)
        assertEquals(0, rig.probe.knocks.size)
        assertEquals(0, rig.browser.lookups)
        rig.wifi.link.value = HOME
        at(20)
        assertEquals(connected, rig.remote.state.value)
    }

    @Test
    fun `losing Wi-Fi while connected says NoWifi and drops presses`() = remoteTest { rig ->
        connect(rig)
        rig.wifi.link.value = null
        at(11)
        assertEquals(TvState.NoWifi, rig.remote.state.value)
        rig.remote.send(TvKey.OK)
        at(100)
        assertEquals(emptyList<Int>(), rig.sender.delivered)
    }

    @Test
    fun `a new network starts a fresh search`() = remoteTest { rig ->
        connect(rig)
        val office = WifiLink(network = "101", ipv4 = "10.0.0.12", prefixLength = 24)
        val tvThere = TvAddress("10.0.0.30")
        rig.probe.answering.clear() // the old address means nothing on the new network
        rig.probe.answering += tvThere.host
        rig.browser.answers = flowOf(tvThere)
        rig.wifi.link.value = office
        at(409)
        // The old TV is checked quietly first, so a network that hasn't really changed doesn't flicker.
        assertEquals(connected, rig.remote.state.value)
        at(410)
        assertEquals(TvState.Searching, rig.remote.state.value)
        at(420)
        assertEquals(TvState.Connected("Baron TV", "10.0.0.30"), rig.remote.state.value)
        assertEquals(listOf(tvThere), rig.store.saved)
    }

    @Test
    fun `retry after NotFound searches again at once`() = remoteTest { rig ->
        rig.remote.start()
        at(7_000)
        assertEquals(TvState.NotFound, rig.remote.state.value)
        // The TV is switched on, then "Try again".
        rig.browser.answers = flowOf(TV)
        rig.probe.answering += TV.host
        rig.remote.retry()
        runCurrent()
        assertEquals(TvState.Searching, rig.remote.state.value)
        at(7_010)
        assertEquals(connected, rig.remote.state.value)
    }

    @Test
    fun `stop leaves nothing running, and start comes back without flashing Searching`() = remoteTest { rig ->
        connect(rig)
        val states = statesOf(rig.remote)
        rig.remote.stop()
        runCurrent()
        assertFalse(rig.wifi.watching)
        val knocks = rig.probe.knocks.size
        at(60_000)
        assertEquals(knocks, rig.probe.knocks.size) // no checks while stopped
        rig.remote.start()
        at(60_010)
        assertEquals(knocks + 1, rig.probe.knocks.size) // one quiet knock on the last address
        assertEquals(listOf<TvState>(connected), states) // never Searching in between
        rig.remote.send(TvKey.HOME)
        at(60_100)
        assertEquals(listOf(TvKey.HOME.code), rig.sender.delivered)
    }

    @Test
    fun `stopping mid-search stops mDNS and the Wi-Fi watch`() = remoteTest { rig ->
        rig.remote.start()
        at(500)
        assertTrue(rig.browser.looking)
        assertTrue(rig.wifi.watching)
        rig.remote.stop()
        runCurrent()
        assertFalse(rig.browser.looking)
        assertFalse(rig.wifi.watching)
        val knocks = rig.probe.knocks.size
        at(30_000)
        assertEquals(knocks, rig.probe.knocks.size)
    }
}
