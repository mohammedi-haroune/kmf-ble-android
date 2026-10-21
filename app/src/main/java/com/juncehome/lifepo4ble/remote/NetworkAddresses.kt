package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The IPv4 addresses a carried phone can use to reach this phone, Wi-Fi first. */
object NetworkAddresses {
    /**
     * Scans the up network interfaces; never throws. An unreadable interface list (any failure of [scan], not
     * only a [java.net.SocketException]) yields no addresses, because the scan runs inside the collection service.
     */
    fun current(scan: () -> List<Pair<String, List<InetAddress>>> = ::scanInterfaces): List<String> {
        val interfaces = try {
            scan()
        } catch (e: Exception) {
            AppLog.w("network address scan failed: ${e.javaClass.simpleName}: ${e.message}", TAG)
            return emptyList()
        }
        return select(interfaces)
    }

    private fun scanInterfaces(): List<Pair<String, List<InetAddress>>> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }
            .map { it.name to it.inetAddresses.toList() }

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
