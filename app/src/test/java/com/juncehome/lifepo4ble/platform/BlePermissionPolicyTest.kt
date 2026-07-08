package com.juncehome.lifepo4ble.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class BlePermissionPolicyTest {
    @Test
    fun api30RequiresFineLocationOnlyAtRuntime() {
        assertEquals(
            setOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.requiredRuntimePermissions(30),
        )
    }

    @Test
    fun api31RequiresBluetoothScanAndConnectAtRuntime() {
        assertEquals(
            setOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.requiredRuntimePermissions(31),
        )
    }
}
