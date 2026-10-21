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
    fun selectKeepsInterfaceOrderAmongOtherInterfaces() {
        val interfaces = listOf(
            "eth0" to listOf(InetAddress.getByName("10.20.30.40")),
            "p2p0" to listOf(InetAddress.getByName("192.168.49.1")),
        )

        assertEquals(listOf("10.20.30.40", "192.168.49.1"), NetworkAddresses.select(interfaces))
    }

    @Test
    fun selectDropsCellularAndTranslationInterfacesSoNetBirdKeepsASlot() {
        val interfaces = listOf(
            "lo" to listOf(InetAddress.getByName("127.0.0.1")),
            "rmnet_data0" to listOf(InetAddress.getByName("10.200.1.2")),
            "v4-rmnet_data0" to listOf(InetAddress.getByName("192.0.0.4")),
            "wlan0" to listOf(InetAddress.getByName("192.168.1.23")),
            "tun0" to listOf(InetAddress.getByName("100.92.4.17")),
        )

        assertEquals(listOf("192.168.1.23", "100.92.4.17"), NetworkAddresses.select(interfaces))
    }

    @Test
    fun selectDropsEveryKnownCellularAndTranslationName() {
        val dropped = listOf("rmnet0", "rmnet_data3", "r_rmnet_data0", "ccmni1", "v4-ccmni1", "clat", "clat4", "dummy0")
        val interfaces = dropped.mapIndexed { index, name ->
            name to listOf(InetAddress.getByName("10.0.0.${index + 1}"))
        }

        assertEquals(emptyList<String>(), NetworkAddresses.select(interfaces))
    }

    @Test
    fun selectRanksWifiClientThenVpnThenOtherWifiAndHotspotThenTheRest() {
        val interfaces = listOf(
            "eth0" to listOf(InetAddress.getByName("10.0.0.9")),
            "swlan0" to listOf(InetAddress.getByName("192.168.43.1")),
            "wlan1" to listOf(InetAddress.getByName("192.168.2.1")),
            "ap0" to listOf(InetAddress.getByName("192.168.44.1")),
            "wt0" to listOf(InetAddress.getByName("100.92.4.17")),
            "tun0" to listOf(InetAddress.getByName("100.64.0.5")),
            "wlan0" to listOf(InetAddress.getByName("192.168.1.23")),
        )

        assertEquals(
            listOf(
                "192.168.1.23", // wlan0
                "100.92.4.17", // wt0
                "100.64.0.5", // tun0
                "192.168.43.1", // swlan0
                "192.168.2.1", // wlan1
                "192.168.44.1", // ap0
                "10.0.0.9", // eth0
            ),
            NetworkAddresses.select(interfaces),
        )
    }

    @Test
    fun selectKeepsAHotspotWifiAfterTheVpnSoNetBirdStaysInTheFirstTwoSlots() {
        val interfaces = listOf(
            "wlan1" to listOf(InetAddress.getByName("192.168.2.1")),
            "tun0" to listOf(InetAddress.getByName("100.92.4.17")),
            "wlan0" to listOf(InetAddress.getByName("192.168.1.23")),
        )

        assertEquals(
            listOf("192.168.1.23", "100.92.4.17", "192.168.2.1"),
            NetworkAddresses.select(interfaces),
        )
    }

    @Test
    fun selectWithOnlyTheVpnUpStillReturnsIt() {
        val interfaces = listOf(
            "rmnet_data0" to listOf(InetAddress.getByName("10.200.1.2")),
            "tun0" to listOf(InetAddress.getByName("100.92.4.17")),
        )

        assertEquals(listOf("100.92.4.17"), NetworkAddresses.select(interfaces))
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
