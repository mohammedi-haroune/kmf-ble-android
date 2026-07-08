package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.protocol.FrameLogEntry
import com.juncehome.lifepo4ble.protocol.KmfReading

data class BleUiState(
    val requiredPermissions: Set<String> = emptySet(),
    val grantedPermissions: Set<String> = emptySet(),
    val hasBleFeature: Boolean = false,
    val hasBluetoothAdapter: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val selectedDevice: ScannedDevice? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serviceUuid: String? = null,
    val notifyUuid: String? = null,
    val writeUuid: String? = null,
    val latestReading: KmfReading = KmfReading(),
    val packetLog: List<FrameLogEntry> = emptyList(),
    val latestError: String? = null,
) {
    val permissionsGranted: Boolean
        get() = grantedPermissions.containsAll(requiredPermissions)

    val readyToScan: Boolean
        get() = permissionsGranted && hasBleFeature && hasBluetoothAdapter && bluetoothEnabled
}
