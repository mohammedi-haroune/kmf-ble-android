package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReadingMerger
import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.PacketDirection
import com.juncehome.lifepo4ble.protocol.PacketFormatter

object BleStateReducer {
    private const val MAX_PACKET_LOG_ENTRIES = 200
    private const val TAG = "KMF-BLE"

    fun reduce(
        state: BleUiState,
        event: BleEvent,
        parser: KmfLineParser,
        nowMs: Long,
    ): BleUiState {
        AppLog.d("reduce(event=${event.javaClass.simpleName}) state=${state.connectionState}", TAG)
        return when (event) {
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
                hasAFrame = false,
                hasBFrame = false,
                latestReading = com.juncehome.lifepo4ble.protocol.KmfReading(),
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
                val payloadText = event.bytes.toString(Charsets.US_ASCII)
                AppLog.d("notification ${event.bytes.size}b hex=${event.bytes.toHexString()} ascii=$payloadText", TAG)
                val frames = parser.offer(event.bytes)
                AppLog.d("parser produced ${frames.size} frame(s)", TAG)
                val reading = frames.fold(state.latestReading) { current, frame ->
                    KmfReadingMerger.apply(current, frame)
                }
                AppLog.d(
                    "merged reading voltage=${reading.voltageV} current=${reading.currentA} power=${reading.powerW} " +
                        "charging=${reading.charging} remainingAh=${reading.remainingAh} capacityAh=${reading.capacityAh} " +
                        "soc=${reading.socPercent} charge=${reading.chargeKwh} discharge=${reading.dischargeKwh}",
                    TAG,
                )
                state.copy(
                    hasAFrame = state.hasAFrame || frames.any { it is KmfFrame.A },
                    hasBFrame = state.hasBFrame || frames.any { it is KmfFrame.B },
                    latestReading = reading,
                    packetLog = state.packetLog.plusBounded(
                        PacketFormatter.toEntry(nowMs, PacketDirection.INBOUND, event.bytes)
                    ),
                )
            }
            is BleEvent.WriteQueued -> {
                AppLog.d("write queued ${event.bytes.size}b hex=${event.bytes.toHexString()}", TAG)
                state.copy(
                    packetLog = state.packetLog.plusBounded(
                        PacketFormatter.toEntry(nowMs, PacketDirection.OUTBOUND, event.bytes)
                    ),
                )
            }
            is BleEvent.WriteCompleted -> {
                AppLog.d("write completed success=${event.success} hex=${event.bytes.toHexString()}", TAG)
                if (event.success) {
                    state
                } else {
                    state.copy(latestError = "BLE write failed")
                }
            }
            is BleEvent.Disconnected -> state.copy(
                scanning = false,
                connectionState = ConnectionState.DISCONNECTED,
                latestError = event.reason,
            )
            is BleEvent.Error -> {
                AppLog.e("BLE error: ${event.message}", event.cause, TAG)
                state.copy(
                    scanning = false,
                    latestError = event.message,
                )
            }
        }
    }

    private fun <T> List<T>.plusBounded(item: T): List<T> =
        (this + item).takeLast(MAX_PACKET_LOG_ENTRIES)
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
