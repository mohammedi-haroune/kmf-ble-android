package com.juncehome.lifepo4ble.platform

object BlePermissionPolicy {
    fun requiredRuntimePermissions(sdkInt: Int): Set<String> = when {
        sdkInt >= 33 -> {
            setOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        sdkInt >= 31 -> setOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )

        else -> setOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Permissions needed to scan for and connect to the meter (first-run step 1). */
    fun bluetoothPermissions(sdkInt: Int): Set<String> =
        requiredRuntimePermissions(sdkInt) - setOfNotNull(notificationPermission(sdkInt))

    /** POST_NOTIFICATIONS on API 33+ (first-run step 4); null on lower API levels. */
    fun notificationPermission(sdkInt: Int): String? =
        if (sdkInt >= 33) android.Manifest.permission.POST_NOTIFICATIONS else null
}
