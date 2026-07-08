package com.juncehome.lifepo4ble.ble

import java.util.UUID

sealed interface BleEvent {
    data object Scanning : BleEvent

    data class ScanResults(
        val devices: List<ScannedDevice>,
    ) : BleEvent

    data class Connecting(
        val device: ScannedDevice,
    ) : BleEvent

    data class Connected(
        val device: ScannedDevice,
    ) : BleEvent

    data class ServicesDiscovered(
        val profile: GattProfile,
    ) : BleEvent

    data class NotificationReceived(
        val characteristicUuid: UUID,
        val bytes: ByteArray,
    ) : BleEvent

    data class WriteQueued(
        val bytes: ByteArray,
    ) : BleEvent

    data class WriteCompleted(
        val bytes: ByteArray,
        val success: Boolean,
    ) : BleEvent

    data class Disconnected(
        val reason: String? = null,
    ) : BleEvent

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : BleEvent
}
