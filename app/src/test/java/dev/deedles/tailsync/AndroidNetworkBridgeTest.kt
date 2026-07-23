package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AndroidNetworkBridgeTest {

    @Test
    fun goFlags_uplinkTypical() {
        // Up|Broadcast|Multicast|Running = 1+2+16+32 = 51
        val flags = AndroidNetworkBridge.goFlags(
            isUp = true,
            isLoopback = false,
            isPointToPoint = false,
            supportsMulticast = true,
            hasBroadcast = true,
        )
        assertEquals(51, flags)
    }

    @Test
    fun goFlags_loopback() {
        // Up|Loopback|Running = 1+4+32 = 37
        val flags = AndroidNetworkBridge.goFlags(
            isUp = true,
            isLoopback = true,
            isPointToPoint = false,
            supportsMulticast = false,
            hasBroadcast = false,
        )
        assertEquals(37, flags)
        assertTrue(flags and AndroidNetworkBridge.FLAG_LOOPBACK != 0)
        assertTrue(flags and AndroidNetworkBridge.FLAG_BROADCAST == 0)
    }

    @Test
    fun goFlags_down() {
        val flags = AndroidNetworkBridge.goFlags(
            isUp = false,
            isLoopback = false,
            isPointToPoint = false,
            supportsMulticast = true,
            hasBroadcast = true,
        )
        assertEquals(
            AndroidNetworkBridge.FLAG_MULTICAST or AndroidNetworkBridge.FLAG_BROADCAST,
            flags,
        )
    }

    @Test
    fun formatCidr_ipv4() {
        val addr = InetAddress.getByName("192.168.1.2")
        assertEquals("192.168.1.2/24", AndroidNetworkBridge.formatCidr(addr, 24))
    }

    @Test
    fun formatCidr_ipv6() {
        val addr = InetAddress.getByName("fe80::1")
        val cidr = AndroidNetworkBridge.formatCidr(addr, 64)
        assertTrue(cidr != null && cidr.endsWith("/64"))
        assertTrue(cidr!!.startsWith("fe80:") || cidr.startsWith("fe80"))
    }

    @Test
    fun formatCidr_clampsPrefix() {
        val addr = InetAddress.getByName("10.0.0.1")
        assertEquals("10.0.0.1/32", AndroidNetworkBridge.formatCidr(addr, 99))
    }

    @Test
    fun formatCidr_rejectsNegativePrefix() {
        val addr = InetAddress.getByName("10.0.0.1")
        assertNull(AndroidNetworkBridge.formatCidr(addr, -1))
    }
}
