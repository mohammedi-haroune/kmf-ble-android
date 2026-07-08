package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReadingMerger
import com.juncehome.lifepo4ble.protocol.PacketDirection
import com.juncehome.lifepo4ble.protocol.PacketFormatter

object BleStateReducer {
    private const val MAX_PACKET_LOG_ENTRIES = 200

    fun reduce(
        state: BleUiState,
        event: BleEvent,
        parser: KmfLineParser,
        nowMs: Long,
    ): BleUiState = when (event) {
        BleEvent.Scanning -> state.copy(
            scanning = true,
            connectionState = ConnectionState.SCANNING,
            latestError = null,
        )
        is BleEvent.ScanResults -> state.copy(
            scanning = true,
            devices = event.devices,
        )
        is BleEvent.Connecting -> state.copy(
            scanning = false,
            selectedDevice = event.device,
            connectionState = ConnectionState.CONNECTING,
            latestError = null,
        )
        is BleEvent.Connected -> state.copy(
            selectedDevice = event.device,
            connectionState = ConnectionState.CONNECTED,
            latestError = null,
        )
        is BleEvent.ServicesDiscovered -> state.copy(
            connectionState = ConnectionState.READY,
            serviceUuid = event.profile.serviceUuid.toString(),
            notifyUuid = event.profile.notifyUuid.toString(),
            writeUuid = event.profile.writeUuid.toString(),
            latestError = null,
        )
        is BleEvent.NotificationReceived -> {
            val frames = parser.offer(event.bytes)
            val reading = frames.fold(state.latestReading) { current, frame ->
                KmfReadingMerger.apply(current, frame)
            }
            state.copy(
                latestReading = reading,
                packetLog = state.packetLog.plusBounded(
                    PacketFormatter.toEntry(nowMs, PacketDirection.INBOUND, event.bytes)
                ),
            )
        }
        is BleEvent.WriteQueued -> state.copy(
            packetLog = state.packetLog.plusBounded(
                PacketFormatter.toEntry(nowMs, PacketDirection.OUTBOUND, event.bytes)
            ),
        )
        is BleEvent.WriteCompleted -> if (event.success) {
            state
        } else {
            state.copy(latestError = "BLE write failed")
        }
        is BleEvent.Disconnected -> state.copy(
            scanning = false,
            connectionState = ConnectionState.DISCONNECTED,
            latestError = event.reason,
        )
        is BleEvent.Error -> state.copy(
            scanning = false,
            latestError = event.message,
        )
    }

    private fun <T> List<T>.plusBounded(item: T): List<T> =
        (this + item).takeLast(MAX_PACKET_LOG_ENTRIES)
}
