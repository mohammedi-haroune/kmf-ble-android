package com.juncehome.lifepo4ble.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun api33IncludesNotificationPermission() {
        assertTrue(
            BlePermissionPolicy.requiredRuntimePermissions(33)
                .contains(android.Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    @Test
    fun bluetoothPermissionsExcludeNotifications() {
        assertEquals(
            setOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.bluetoothPermissions(33),
        )
        assertEquals(
            setOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.bluetoothPermissions(30),
        )
    }

    @Test
    fun notificationPermissionOnlyFromApi33() {
        assertEquals(null, BlePermissionPolicy.notificationPermission(32))
        assertEquals(
            android.Manifest.permission.POST_NOTIFICATIONS,
            BlePermissionPolicy.notificationPermission(33),
        )
    }
}
