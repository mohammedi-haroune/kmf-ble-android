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
}
