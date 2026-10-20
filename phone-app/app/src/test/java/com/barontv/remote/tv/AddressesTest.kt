package com.barontv.remote.tv

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressesTest {

    @Test
    fun `a 24 is every host but the network, the broadcast and the phone`() {
        val hosts = subnetHosts("192.168.1.37", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertFalse("192.168.1.37" in hosts)
        assertTrue("192.168.1.25" in hosts)
    }

    @Test
    fun `a 16 is cut down to the phone's own 24`() {
        val hosts = subnetHosts("10.0.5.9", 16)
        // In a /16, 10.0.5.0 and 10.0.5.255 are ordinary hosts; its own network and broadcast addresses
        // (10.0.0.0 and 10.0.255.255) are outside the phone's /24 anyway.
        assertEquals(255, hosts.size)
        assertEquals("10.0.5.0", hosts.first())
        assertEquals("10.0.5.255", hosts.last())
        assertFalse("10.0.5.9" in hosts)
        assertTrue(hosts.all { it.startsWith("10.0.5.") })
    }

    @Test
    fun `a 23 keeps the broadcast out when it falls in the phone's 24`() {
        // 192.168.0.0/23 runs to 192.168.1.255, its broadcast; 192.168.1.0 is a host in it.
        val hosts = subnetHosts("192.168.1.37", 23)
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.0", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `a 30 has two hosts, one of them the phone`() {
        assertEquals(listOf("192.168.1.2"), subnetHosts("192.168.1.1", 30))
        assertEquals(listOf("192.168.1.1"), subnetHosts("192.168.1.2", 30))
        assertEquals(listOf("10.9.8.6"), subnetHosts("10.9.8.5", 30))
    }

    @Test
    fun `a 31 is two hosts with no network or broadcast address`() {
        assertEquals(listOf("10.0.0.1"), subnetHosts("10.0.0.0", 31))
        assertEquals(listOf("10.0.0.0"), subnetHosts("10.0.0.1", 31))
    }

    @Test
    fun `a 32 has nobody else in it`() {
        assertEquals(emptyList<String>(), subnetHosts("10.0.0.7", 32))
    }

    @Test
    fun `prefixes out of range are clamped`() {
        // Read as /0: still only the phone's /24, and the /0's ends (0.0.0.0, 255.255.255.255) are far off.
        assertEquals(255, subnetHosts("172.16.3.4", 0).size)
        assertEquals(255, subnetHosts("172.16.3.4", -3).size)
        // Read as /32.
        assertEquals(emptyList<String>(), subnetHosts("172.16.3.4", 40))
    }

    @Test
    fun `anything but a dotted quad gives nothing to scan`() {
        assertEquals(emptyList<String>(), subnetHosts("not an address", 24))
        assertEquals(emptyList<String>(), subnetHosts("192.168.1.300", 24))
        assertEquals(emptyList<String>(), subnetHosts("192.168.1", 24))
        assertEquals(emptyList<String>(), subnetHosts("fe80::1", 64))
    }

    @Test
    fun `the scan starts next to the TV's last address`() {
        val hosts = subnetHosts("192.168.1.37", 24)
        val ordered = nearestFirst(hosts, near = "192.168.1.20")
        assertEquals(listOf("192.168.1.20", "192.168.1.19", "192.168.1.21", "192.168.1.18", "192.168.1.22"), ordered.take(5))
        assertEquals(hosts.toSet(), ordered.toSet())
        // No last address, or not an IPv4 one: the order stays as it was.
        assertEquals(hosts, nearestFirst(hosts, near = null))
        assertEquals(hosts, nearestFirst(hosts, near = "Android.local"))
    }

    @Test
    fun `mDNS answers give their IPv4 address`() {
        val v6 = InetAddress.getByName("fe80::1")
        val v4 = InetAddress.getByName("192.168.1.25")
        assertEquals(TvAddress("192.168.1.25", 5511), addressOf(listOf(v6, v4), 5511))
        assertEquals(TvAddress("192.168.1.25", 6000), addressOf(listOf(v4), 6000))
        // A port mDNS got wrong falls back to CloudWalker's.
        assertEquals(TvAddress("192.168.1.25", 5511), addressOf(listOf(v4), 0))
        // Only IPv6, or nothing: not usable.
        assertNull(addressOf(listOf(v6), 5511))
        assertNull(addressOf(listOf(null), 5511))
        assertNull(addressOf(emptyList(), 5511))
    }

    @Test
    fun `addresses turn into numbers and back`() {
        assertEquals(0xC0A80119L, ipv4ToLong("192.168.1.25"))
        assertEquals("192.168.1.25", longToIpv4(0xC0A80119L))
        assertEquals("0.0.0.0", longToIpv4(0L))
        assertEquals("255.255.255.255", longToIpv4(0xFFFFFFFFL))
        assertNull(ipv4ToLong("1.2.3"))
        assertNull(ipv4ToLong("1.2.3.-4"))
        assertNull(ipv4ToLong("1.2.3.4444"))
        assertNull(ipv4ToLong(""))
    }

    @Test
    fun `each key becomes CloudWalker's input request`() {
        assertEquals("http://192.168.1.25:5511/input/?keycode=24", keyUrl(TV, TvKey.VOLUME_UP.code).toString())
        assertEquals("http://10.0.0.30:5511/input/?keycode=178", keyUrl(TvAddress("10.0.0.30"), TvKey.INPUT.code).toString())
        val expected = mapOf(
            TvKey.UP to 19, TvKey.DOWN to 20, TvKey.LEFT to 21, TvKey.RIGHT to 22, TvKey.OK to 23,
            TvKey.BACK to 4, TvKey.HOME to 3, TvKey.MENU to 82, TvKey.INPUT to 178, TvKey.VOLUME_UP to 24,
            TvKey.VOLUME_DOWN to 25, TvKey.MUTE to 164, TvKey.PLAY_PAUSE to 85,
        )
        for ((key, code) in expected) {
            assertEquals("/input/?keycode=$code", keyUrl(TV, key.code).file)
        }
    }
}
