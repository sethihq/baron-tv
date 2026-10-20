package com.barontv.remote.tv

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.math.abs

/** The widest subnet the scan covers: a /24 is 254 addresses, a /16 would be 65,534. */
private const val WIDEST_SCAN_PREFIX = 24

/**
 * The addresses the scan knocks on: every host in the phone's IPv4 subnet, but never more than the /24
 * around the phone, however wide the subnet is. Leaves out the phone itself and the subnet's own first
 * (network) and last (broadcast) addresses. When a wider subnet is cut down to the phone's /24, that /24's
 * .0 and .255 are ordinary hosts in it, so they stay in. On a /31 both addresses are hosts (RFC 3021).
 */
internal fun subnetHosts(phone: String, prefixLength: Int): List<String> {
    val ip = ipv4ToLong(phone) ?: return emptyList()
    val prefix = prefixLength.coerceIn(0, 32)
    val hostBits = 32 - prefix
    val network = ip and maskOf(hostBits)
    val broadcast = network or ((1L shl hostBits) - 1)
    val windowBits = minOf(hostBits, 32 - WIDEST_SCAN_PREFIX)
    val first = ip and maskOf(windowBits)
    val last = first or ((1L shl windowBits) - 1)
    val notHosts = if (prefix >= 31) emptySet() else setOf(network, broadcast)
    return (first..last).filter { it != ip && it !in notHosts }.map(::longToIpv4)
}

/**
 * [hosts] with the ones closest to [near] first (lower first on a tie). The TV's address changes at every
 * boot but tends to land near its last one (.20, .24, then .25), so the scan usually hits it in the first
 * handful of knocks. Without a usable [near], the order is left alone.
 */
internal fun nearestFirst(hosts: List<String>, near: String?): List<String> {
    val anchor = near?.let(::ipv4ToLong) ?: return hosts
    return hosts.sortedBy { host -> ipv4ToLong(host)?.let { abs(it - anchor) } ?: Long.MAX_VALUE }
}

/**
 * The address to use from an mDNS answer: its IPv4 one, or null without one. The TV's Wi-Fi is IPv4, as
 * are the saved address and the scan, and a link-local IPv6 address can't go in a URL that Android's HTTP
 * stack accepts. A port mDNS gets wrong falls back to CloudWalker's.
 */
internal fun addressOf(addresses: List<InetAddress?>, port: Int): TvAddress? {
    val v4 = addresses.firstOrNull { it is Inet4Address } ?: return null
    val host = v4.hostAddress ?: return null
    return TvAddress(host, if (port in 1..65_535) port else CLOUDWALKER_PORT)
}

/** "192.168.1.25" as a number, so subnets can be worked out with masks. Null if it isn't a dotted quad. */
internal fun ipv4ToLong(text: String): Long? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
        val octet = part.toInt()
        if (octet > 255) return null
        value = (value shl 8) or octet.toLong()
    }
    return value
}

internal fun longToIpv4(value: Long): String =
    "${(value shr 24) and 255}.${(value shr 16) and 255}.${(value shr 8) and 255}.${value and 255}"

/** The mask that clears the lowest [hostBits] bits of a 32-bit address. */
private fun maskOf(hostBits: Int): Long = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
