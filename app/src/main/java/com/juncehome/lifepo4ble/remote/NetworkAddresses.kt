package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** The IPv4 addresses a carried phone can use to reach this phone, Wi-Fi first. */
object NetworkAddresses {
    /** Scans the up network interfaces; never throws, an unreadable interface list yields no addresses. */
    fun current(): List<String> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp }
                .map { it.name to it.inetAddresses.toList() }
        } catch (e: SocketException) {
            AppLog.w("network address scan failed: ${e.message}", TAG)
            return emptyList()
        }
        return select(interfaces)
    }

    /**
     * IPv4 only, without loopback and link-local (169.254.x.x). Keeps the given interface order except that
     * interfaces named wlan* come first.
     */
    fun select(interfaces: List<Pair<String, List<InetAddress>>>): List<String> {
        val (wifi, other) = interfaces.partition { (name, _) -> name.startsWith(WIFI_PREFIX) }
        return (wifi + other)
            .flatMap { (_, addresses) -> addresses }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    }

    private const val WIFI_PREFIX = "wlan"
    private const val TAG = "KMF-BLE"
}
