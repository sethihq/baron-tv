@file:OptIn(ExperimentalCoroutinesApi::class)

package com.barontv.remote.tv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

// Stand-ins for the Wi-Fi, mDNS, sockets and storage, all running on the test's virtual clock.

/** The phone at .37 on a usual home /24. */
internal val HOME = WifiLink(network = "100", ipv4 = "192.168.1.37", prefixLength = 24)

/** Where the TV is in most tests. */
internal val TV = TvAddress("192.168.1.25")

internal class FakeWifi(initial: WifiLink?) : WifiWatcher {
    val link = MutableStateFlow(initial)
    var watching = false

    override fun links(): Flow<WifiLink?> = link
        .onStart { watching = true }
        .onCompletion { watching = false }
}

internal class FakeBrowser : TvBrowser {
    /** What mDNS will say. Silent unless a test says otherwise. */
    var answers: Flow<TvAddress> = flow { awaitCancellation() }
    var lookups = 0
    var looking = false

    override fun find(): Flow<TvAddress> = answers
        .onStart {
            lookups++
            looking = true
        }
        .onCompletion { looking = false }
}

/** Hosts in [answering] accept at once; any other address stays silent until the timeout. */
internal class FakeProbe(private val clock: TestCoroutineScheduler) : PortProbe {
    data class Knock(val atMs: Long, val host: String)

    val answering = mutableSetOf<String>()
    val knocks = mutableListOf<Knock>()

    override suspend fun isOpen(address: TvAddress, timeoutMs: Int): Boolean {
        knocks += Knock(clock.currentTime, address.host)
        return if (address.host in answering) {
            delay(2)
            true
        } else {
            delay(timeoutMs.toLong())
            false
        }
    }
}

internal class FakeSender : KeySender {
    var reachable = true
    var latencyMs = 10L
    val delivered = mutableListOf<Int>()

    override suspend fun send(address: TvAddress, code: Int): Boolean {
        delay(latencyMs)
        if (!reachable) return false
        delivered += code
        return true
    }
}

internal class FakeStore(var address: TvAddress? = null) : AddressStore {
    val saved = mutableListOf<TvAddress>()

    /** A store that throws, as a corrupt preferences file might. */
    var broken = false

    override fun load(): TvAddress? {
        check(!broken) { "unreadable" }
        return address
    }

    override fun save(address: TvAddress) {
        check(!broken) { "unwritable" }
        this.address = address
        saved += address
    }
}

/** A remote wired to fakes, on the test's virtual clock. */
internal class Rig(clock: TestCoroutineScheduler) {
    val wifi = FakeWifi(HOME)
    val browser = FakeBrowser()
    val probe = FakeProbe(clock)
    val sender = FakeSender()
    val store = FakeStore()
    val remote = CloudWalkerRemote(
        wifi = wifi,
        browser = browser,
        probe = probe,
        sender = sender,
        store = store,
        dispatcher = StandardTestDispatcher(clock),
        log = { println("[${clock.currentTime} ms] $it") },
    )
}
