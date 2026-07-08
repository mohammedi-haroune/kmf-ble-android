package com.juncehome.lifepo4ble.platform

object BlePermissionPolicy {
    fun requiredRuntimePermissions(sdkInt: Int): Set<String> =
        if (sdkInt >= 31) {
            setOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            setOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
