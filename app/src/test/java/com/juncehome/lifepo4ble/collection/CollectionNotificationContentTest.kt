package com.juncehome.lifepo4ble.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionNotificationContentTest {
    private val discharging = NotificationReading(
        timestampMs = 1_000L,
        voltageV = 13.28,
        currentA = -4.1,
        powerW = -54.448,
        charging = false,
        minutesRemaining = 2_343,
        socPercent = 89.177,
        remainingAh = 160.156,
        capacityAh = 180.0,
    )

    private fun state(phase: CollectionPhase, error: String? = null) =
        CollectionState(phase = phase, visibility = CollectionVisibility.entries.first(), error = error)

    @Test
    fun dischargingShowsSignedValuesAndTimeLeft() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), discharging, NOW)

        assertEquals("Discharging · 89.18% · 160.16 / 180.00 Ah", content.title)
        assertEquals("13.28 V · -4.10 A · -54.45 W", content.text)
        assertEquals("1d 15h left", content.subText)
    }

    @Test
    fun chargingShowsUnsignedValuesAndTimeToFull() {
        val charging = discharging.copy(currentA = 4.1, powerW = 54.4, charging = true, minutesRemaining = 113)

        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), charging, NOW)

        assertEquals("Charging · 89.18% · 160.16 / 180.00 Ah", content.title)
        assertEquals("13.28 V · 4.10 A · 54.40 W", content.text)
        assertEquals("1h 53m to full", content.subText)
    }

    @Test
    fun nearZeroCurrentShowsIdleUnsignedWithoutTime() {
        val idle = discharging.copy(currentA = -0.01, powerW = -0.1)

        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), idle, NOW)

        assertEquals("Idle · 89.18% · 160.16 / 180.00 Ah", content.title)
        assertEquals("13.28 V · 0.01 A · 0.10 W", content.text)
        assertNull(content.subText)
    }

    @Test
    fun missingEstimateHidesTimeLeft() {
        val content = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging.copy(minutesRemaining = 0),
            NOW,
        )

        assertNull(content.subText)
    }

    @Test
    fun collectingWithoutSampleNeverShowsZeroSoc() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), null, NOW)

        assertEquals("Connecting", content.title)
        assertEquals("No reading yet", content.text)
        assertNull(content.subText)
    }

    @Test
    fun notCollectingShowsStateWordSocCapacityAndLastValues() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.WAITING_FOR_DEVICE), discharging, NOW)

        assertEquals("Out of range · 89.18% · 160.16 / 180.00 Ah", content.title)
        assertEquals("Last: 13.28 V · -4.10 A · -54.45 W", content.text)
        assertNull(content.subText)
    }

    @Test
    fun errorWithoutSampleShowsPausedAndNoReading() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.ERROR, error = "GATT 133"), null, NOW)

        assertEquals("Paused", content.title)
        assertEquals("No reading yet", content.text)
    }

    @Test
    fun staleSampleWhileCollectingIsNotShownAsLive() {
        val content = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging,
            discharging.timestampMs + 61_000L,
        )

        assertEquals("Collecting · 89.18% · 160.16 / 180.00 Ah", content.title)
        assertEquals("Last: 13.28 V · -4.10 A · -54.45 W", content.text)
        assertNull(content.subText)
    }

    @Test
    fun stateWordsAreShort() {
        assertEquals("Connecting", CollectionNotificationContent.stateWord(state(CollectionPhase.BOOTSTRAPPING)))
        assertEquals("Bluetooth off", CollectionNotificationContent.stateWord(state(CollectionPhase.BLUETOOTH_OFF)))
        assertEquals("Stopped", CollectionNotificationContent.stateWord(state(CollectionPhase.STOPPED)))
    }

    @Test
    fun fractionalCapacityKeepsTwoDecimalsAndMissingCapacityShowsRemainingOnly() {
        val fractional = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging.copy(capacityAh = 100.5),
            NOW,
        )
        val missing = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging.copy(capacityAh = 0.0),
            NOW,
        )

        assertEquals("Discharging · 89.18% · 160.16 / 100.50 Ah", fractional.title)
        assertEquals("Discharging · 89.18% · 160.16 Ah", missing.title)
    }

    @Test
    fun socIsClampedToPercentRange() {
        val content = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging.copy(socPercent = 104.0),
            NOW,
        )

        assertEquals("Discharging · 100.00% · 160.16 / 180.00 Ah", content.title)
    }

    private companion object {
        const val NOW = 2_000L
    }
}
