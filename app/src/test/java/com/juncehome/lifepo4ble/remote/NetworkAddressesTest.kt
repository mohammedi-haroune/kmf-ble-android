package com.juncehome.lifepo4ble.remote

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAddressesTest {
    @Test
    fun selectKeepsIpv4WifiFirstAndDropsLoopbackLinkLocalAndIpv6() {
        val interfaces = listOf(
            "lo" to listOf(InetAddress.getByName("127.0.0.1")),
            "wt0" to listOf(InetAddress.getByName("100.92.4.17"), InetAddress.getByName("fd00::1")),
            "wlan0" to listOf(InetAddress.getByName("192.168.1.23"), InetAddress.getByName("169.254.3.4")),
        )

        assertEquals(listOf("192.168.1.23", "100.92.4.17"), NetworkAddresses.select(interfaces))
    }

    @Test
    fun selectKeepsInterfaceOrderAmongNonWifiInterfaces() {
        val interfaces = listOf(
            "rmnet0" to listOf(InetAddress.getByName("10.20.30.40")),
            "wt0" to listOf(InetAddress.getByName("100.92.4.17")),
        )

        assertEquals(listOf("10.20.30.40", "100.92.4.17"), NetworkAddresses.select(interfaces))
    }

    @Test
    fun selectReturnsEmptyWithoutUsableAddresses() {
        val interfaces = listOf(
            "lo" to listOf(InetAddress.getByName("127.0.0.1")),
            "wlan0" to listOf(InetAddress.getByName("fe80::1")),
        )

        assertEquals(emptyList<String>(), NetworkAddresses.select(interfaces))
    }
}
