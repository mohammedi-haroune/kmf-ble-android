package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionState
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
    val collectionConfigurationLoaded: Boolean = false,
    /** Latest state published by the collection service; null until the service has published. */
    val collectionState: CollectionState? = null,
    /** Receive times of parsed inbound frames over roughly the last minute (Now tab Data link). */
    val inboundFrameTimesMs: List<Long> = emptyList(),
    /**
     * Bluetooth-only runtime permissions (scan/connect, or location before API 31). Empty means
     * "same as [requiredPermissions]" so older callers keep the previous all-or-nothing behavior.
     */
    val bluetoothPermissions: Set<String> = emptySet(),
    /** POST_NOTIFICATIONS on API 33+, null where Android grants notifications implicitly. */
    val notificationPermission: String? = null,
    /** Address of the saved meter snapshot; null when no meter is saved. */
    val savedMeterAddress: String? = null,
    /** Advertised name of the saved meter snapshot, if any. */
    val savedMeterName: String? = null,
    val savedMeterLoaded: Boolean = false,
    /** Android CompanionDeviceManager holds an association for the saved meter. */
    val companionAssociated: Boolean = false,
    /** Persisted once the user finished the first-run flow ("Open dashboard"). */
    val firstRunCompleted: Boolean = false,
    val firstRunCompletedLoaded: Boolean = false,
    /**
     * Device tab one-off test connection is active (connecting or connected). It never persists
     * samples and never reconnects; it ends on disconnect, failure or the user's Disconnect.
     */
    val testConnectionActive: Boolean = false,
    /** Short outcome of the last test connection ("Disconnected", failure reason); null when none. */
    val testConnectionResult: String? = null,
) {
    val permissionsGranted: Boolean
        get() = grantedPermissions.containsAll(requiredPermissions)

    val bluetoothPermissionsGranted: Boolean
        get() = grantedPermissions.containsAll(bluetoothPermissions.ifEmpty { requiredPermissions })

    val notificationPermissionGranted: Boolean
        get() = notificationPermission == null || notificationPermission in grantedPermissions

    /** All persisted inputs of the first-run gate have been read at least once. */
    val setupStateLoaded: Boolean
        get() = collectionConfigurationLoaded && savedMeterLoaded && firstRunCompletedLoaded

    val readyToScan: Boolean
        get() = permissionsGranted && hasBleFeature && hasBluetoothAdapter && bluetoothEnabled

    val kmfBootstrapReady: Boolean
        get() = hasAFrame && hasBFrame

    val manualTransportControlsEnabled: Boolean
        get() = collectionConfigurationLoaded && !autonomousCollectionConfigured
}
