package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.ui.now.NowScreenReducer
import java.util.Locale
import kotlin.math.abs

/** Battery values the collection notification shows; taken only from saved (complete A+B+C) samples. */
data class NotificationReading(
    val timestampMs: Long,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val charging: Boolean,
    val minutesRemaining: Int,
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
            minutesRemaining = sample.minutesRemaining,
            socPercent = sample.socPercent,
            remainingAh = sample.remainingAh,
            capacityAh = sample.capacityAh,
        )
    }
}

/**
 * Text of the collection notification for one state and the newest sample. Every state uses one layout:
 * header "Cell Watch [· time left]", title "State · SoC · remaining / full Ah", text "[Last: ]V · A · W".
 */
data class CollectionNotificationContent(
    val title: String,
    val text: String,
    /** Shown next to the app name in the header; null when there is no live estimate. */
    val subText: String?,
) {
    companion object {
        fun from(state: CollectionState, reading: NotificationReading?, nowMs: Long): CollectionNotificationContent {
            // A sample from an earlier session or a stalled link must not pass as live.
            val live = state.phase == CollectionPhase.COLLECTING &&
                reading != null &&
                nowMs - reading.timestampMs <= STALE_READING_MS
            if (reading == null) {
                val title = if (state.phase == CollectionPhase.COLLECTING) "Connecting" else stateWord(state)
                return CollectionNotificationContent(title = title, text = "No reading yet", subText = null)
            }
            val word = if (live) direction(reading) else stateWord(state)
            val values = "${volts(reading)} · ${amps(reading)} · ${watts(reading)}"
            return CollectionNotificationContent(
                title = "$word · ${soc(reading)} · ${ah(reading)}",
                text = if (live) values else "Last: $values",
                subText = if (live) timeLeft(reading) else null,
            )
        }

        /** One or two words for a state without a live reading. */
        fun stateWord(state: CollectionState): String = when (state.phase) {
            CollectionPhase.COLLECTING -> "Collecting"
            CollectionPhase.CONNECTING,
            CollectionPhase.BOOTSTRAPPING -> "Connecting"
            CollectionPhase.WAITING_FOR_DEVICE -> "Out of range"
            CollectionPhase.BLUETOOTH_OFF -> "Bluetooth off"
            CollectionPhase.ERROR -> "Paused"
            CollectionPhase.STOPPED -> "Stopped"
        }

        private fun isIdle(reading: NotificationReading): Boolean = abs(reading.currentA) < IDLE_CURRENT_A

        private fun isDischarging(reading: NotificationReading): Boolean = !isIdle(reading) && !reading.charging

        private fun direction(reading: NotificationReading): String = when {
            isIdle(reading) -> "Idle"
            reading.charging -> "Charging"
            else -> "Discharging"
        }

        /** "1d 15h left" when discharging, "1h 53m to full" when charging; null when idle or unknown. */
        private fun timeLeft(reading: NotificationReading): String? {
            if (isIdle(reading) || reading.minutesRemaining <= 0) return null
            val duration = NowScreenReducer.formatDuration(reading.minutesRemaining)
            return if (reading.charging) "$duration to full" else "$duration left"
        }

        private fun soc(reading: NotificationReading): String =
            String.format(Locale.US, "%.2f%%", reading.socPercent.coerceIn(0.0, 100.0))

        /** Remaining of full capacity, formatted like the Now tab ("160.20 / 180.00 Ah"). */
        private fun ah(reading: NotificationReading): String {
            val remaining = String.format(Locale.US, "%.2f", reading.remainingAh)
            val capacity = reading.capacityAh
            if (capacity <= 0.0) return "$remaining Ah"
            val capacityText = String.format(Locale.US, "%.2f", capacity)
            return "$remaining / $capacityText Ah"
        }

        private fun volts(reading: NotificationReading): String =
            String.format(Locale.US, "%.2f V", reading.voltageV)

        /** Discharging current and power carry a "-" sign; charging and idle stay unsigned. */
        private fun sign(reading: NotificationReading): String = if (isDischarging(reading)) "-" else ""

        private fun amps(reading: NotificationReading): String =
            sign(reading) + String.format(Locale.US, "%.2f A", abs(reading.currentA))

        private fun watts(reading: NotificationReading): String =
            sign(reading) + String.format(Locale.US, "%.2f W", abs(reading.powerW))

        private const val IDLE_CURRENT_A = 0.05
        private const val STALE_READING_MS = 60_000L
    }
}
