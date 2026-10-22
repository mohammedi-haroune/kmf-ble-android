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
     * IPv4 only, without loopback and link-local (169.254.x.x). The Device tab shows two slots, so the order
     * decides what a carried phone can try: the Wi-Fi client (wlan0) first, then the VPN (tun*, wt*: NetBird), then
     * other Wi-Fi, hotspot and access point interfaces, then the rest in the given order. Cellular and translation
     * interfaces (rmnet, ccmni, v4-*, clat, dummy) are dropped; they are not reachable from another phone and would
     * otherwise push the VPN address out of the slots.
     */
    fun select(interfaces: List<Pair<String, List<InetAddress>>>): List<String> =
        interfaces
            .filterNot { (name, _) -> DROPPED_PREFIXES.any(name::startsWith) }
            .sortedBy { (name, _) -> rank(name) }
            .flatMap { (_, addresses) -> addresses }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }

    /** Lower first; [sortedBy] is stable, so equal ranks keep the kernel order. */
    private fun rank(name: String): Int = when {
        name.startsWith(WIFI_CLIENT_PREFIX) -> 0
        VPN_PREFIXES.any(name::startsWith) -> 1
        OTHER_WIFI_PREFIXES.any(name::startsWith) -> 2
        else -> 3
    }

    private const val WIFI_CLIENT_PREFIX = "wlan0"
    private val VPN_PREFIXES = listOf("tun", "wt")
    private val OTHER_WIFI_PREFIXES = listOf("wlan", "swlan", "ap")
    private val DROPPED_PREFIXES = listOf("rmnet", "r_rmnet", "ccmni", "v4-", "clat", "dummy")
    private const val TAG = "KMF-BLE"
}
