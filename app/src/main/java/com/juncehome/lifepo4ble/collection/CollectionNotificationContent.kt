package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Battery values the collection notification shows; taken only from saved (complete A+B+C) samples. */
data class NotificationReading(
    val timestampMs: Long,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val charging: Boolean,
    val socPercent: Double,
    val remainingAh: Double,
    val capacityAh: Double,
) {
    companion object {
        fun from(sample: KmfBatterySampleEntity): NotificationReading = NotificationReading(
            timestampMs = sample.timestampMs,
            voltageV = sample.voltageV,
            currentA = sample.currentA,
            powerW = sample.powerW,
            charging = sample.charging,
            socPercent = sample.socPercent,
            remainingAh = sample.remainingAh,
            capacityAh = sample.capacityAh,
        )
    }
}

/** Title, body and timestamp of the collection notification for one state and the newest sample. */
data class CollectionNotificationContent(
    val title: String,
    val text: String,
    /** Sample time shown as the notification's "when"; null hides the timestamp. */
    val whenMs: Long?,
) {
    companion object {
        fun from(state: CollectionState, reading: NotificationReading?, nowMs: Long): CollectionNotificationContent {
            if (state.phase == CollectionPhase.COLLECTING) {
                return if (reading == null) {
                    CollectionNotificationContent(
                        title = "Collecting battery data",
                        text = "Waiting for the first full reading",
                        whenMs = null,
                    )
                } else if (nowMs - reading.timestampMs > STALE_READING_MS) {
                    // A sample from an earlier session must not pass as live while the new one fills in.
                    CollectionNotificationContent(
                        title = "Collecting battery data",
                        text = "Last: ${soc(reading)} · ${volts(reading)}",
                        whenMs = reading.timestampMs,
                    )
                } else {
                    CollectionNotificationContent(
                        title = "${direction(reading)} · ${soc(reading)} · ${ah(reading)}",
                        text = "${volts(reading)} · ${amps(reading)} · ${watts(reading)}",
                        whenMs = reading.timestampMs,
                    )
                }
            }
            val status = statusText(state)
            return if (reading == null) {
                CollectionNotificationContent(title = status, text = "No reading yet", whenMs = null)
            } else {
                CollectionNotificationContent(
                    title = status,
                    text = "Last: ${soc(reading)} · ${volts(reading)}",
                    whenMs = reading.timestampMs,
                )
            }
        }

        fun statusText(state: CollectionState): String = when (state.phase) {
            CollectionPhase.COLLECTING -> "Collecting battery data"
            CollectionPhase.CONNECTING,
            CollectionPhase.BOOTSTRAPPING -> "Connecting to meter"
            CollectionPhase.WAITING_FOR_DEVICE -> "Meter is out of range"
            CollectionPhase.BLUETOOTH_OFF -> "Bluetooth is off"
            CollectionPhase.ERROR -> "Collection paused: ${state.error ?: "connection error"}"
            CollectionPhase.STOPPED -> "Collection stopped"
        }

        private fun direction(reading: NotificationReading): String = when {
            abs(reading.currentA) < IDLE_CURRENT_A -> "Idle"
            reading.charging -> "Charging"
            else -> "Discharging"
        }

        private fun soc(reading: NotificationReading): String =
            String.format(Locale.US, "%.0f%%", reading.socPercent.coerceIn(0.0, 100.0))

        /** Remaining of full capacity, formatted like the Now tab ("160.2 / 180 Ah"). */
        private fun ah(reading: NotificationReading): String {
            val remaining = String.format(Locale.US, "%.1f", reading.remainingAh)
            val capacity = reading.capacityAh
            if (capacity <= 0.0) return "$remaining Ah"
            val capacityText = if (capacity == capacity.roundToInt().toDouble()) {
                capacity.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.1f", capacity)
            }
            return "$remaining / $capacityText Ah"
        }

        private fun volts(reading: NotificationReading): String =
            String.format(Locale.US, "%.2f V", reading.voltageV)

        private fun amps(reading: NotificationReading): String =
            String.format(Locale.US, "%.2f A", abs(reading.currentA))

        private fun watts(reading: NotificationReading): String =
            String.format(Locale.US, "%.1f W", abs(reading.powerW))

        private const val IDLE_CURRENT_A = 0.05
        private const val STALE_READING_MS = 60_000L
    }
}
