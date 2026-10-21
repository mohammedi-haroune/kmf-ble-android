package com.juncehome.lifepo4ble.ble

/**
 * Decides which scan results are KMF meters, the only devices this app supports.
 *
 * KMF meters advertise their Bluetooth module name (`BTGxxx`); some firmware or renamed modules
 * carry `KMF` in the name instead. Unnamed devices cannot be identified and are excluded.
 */
object KmfMeterFilter {
    private const val MODULE_PREFIX = "BTG"
    private const val BRAND_MARKER = "KMF"

    fun isKmfMeter(name: String?): Boolean {
        val normalized = name?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized.startsWith(MODULE_PREFIX) || normalized.contains(BRAND_MARKER)
    }

    fun isKmfMeter(device: ScannedDevice): Boolean = isKmfMeter(device.name)
}
