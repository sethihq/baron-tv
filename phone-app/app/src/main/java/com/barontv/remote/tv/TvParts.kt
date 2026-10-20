package com.barontv.remote.tv

import kotlinx.coroutines.flow.Flow

// The pieces CloudWalkerRemote is built from. Each one hides an Android API behind a few lines of plain
// Kotlin, so the searching, checking and sending logic runs in JVM tests with fakes in their place.

/** Where the TV's remote server answers: an IPv4 address on the Wi-Fi and a port. */
internal data class TvAddress(val host: String, val port: Int = CLOUDWALKER_PORT)

/**
 * The phone's Wi-Fi, as far as finding the TV goes. [network] tells one connection from the next (it
 * changes when the phone joins a network again); [ipv4] and [prefixLength] are the phone's own address on
 * it, for the subnet scan. [ipv4] is null until the router has given the phone an address.
 */
internal data class WifiLink(val network: String, val ipv4: String?, val prefixLength: Int)

/** Reports the phone's Wi-Fi: the current link straight away, then again whenever it changes. */
internal interface WifiWatcher {
    /** Null means no Wi-Fi. */
    fun links(): Flow<WifiLink?>
}

/** Finds the TV by name over mDNS (`_cloudwalker._tcp`). */
internal interface TvBrowser {
    /**
     * Each address the TV's service resolves to, as it's found. The flow ends early if mDNS can't run at
     * all, and stops looking (letting go of the Wi-Fi multicast lock) as soon as its collector stops.
     */
    fun find(): Flow<TvAddress>
}

/** Checks whether something accepts a TCP connection, without sending it anything. */
internal interface PortProbe {
    suspend fun isOpen(address: TvAddress, timeoutMs: Int): Boolean
}

/** Sends one key press to the TV. */
internal interface KeySender {
    /** False when the TV couldn't be reached at all. */
    suspend fun send(address: TvAddress, code: Int): Boolean
}

/** Remembers the last address the TV answered on. */
internal interface AddressStore {
    fun load(): TvAddress?
    fun save(address: TvAddress)
}
