package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.protocol.FrameLogEntry
import com.juncehome.lifepo4ble.protocol.KmfReading

enum class AutonomousCollectionStatus {
    NOT_CONFIGURED,
    SETUP_IN_PROGRESS,
    CONFIGURED,
    UNSUPPORTED,
    ERROR,
}

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
    val hasAFrame: Boolean = false,
    val hasBFrame: Boolean = false,
    val latestReading: KmfReading = KmfReading(),
    val lastReadingTimestampMs: Long? = null,
    val packetLog: List<FrameLogEntry> = emptyList(),
    val latestError: String? = null,
    val canConfigureAutonomousCollection: Boolean = false,
    val autonomousCollectionStatus: AutonomousCollectionStatus = AutonomousCollectionStatus.NOT_CONFIGURED,
    val autonomousCollectionDetail: String? = null,
    val autonomousCollectionConfigured: Boolean = false,
) {
    val permissionsGranted: Boolean
        get() = grantedPermissions.containsAll(requiredPermissions)

    val readyToScan: Boolean
        get() = permissionsGranted && hasBleFeature && hasBluetoothAdapter && bluetoothEnabled

    val kmfBootstrapReady: Boolean
        get() = hasAFrame && hasBFrame
}
