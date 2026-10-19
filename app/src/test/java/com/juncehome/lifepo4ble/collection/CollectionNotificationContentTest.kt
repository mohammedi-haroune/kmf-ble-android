package com.juncehome.lifepo4ble.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionNotificationContentTest {
    private val discharging = NotificationReading(
        timestampMs = 1_000L,
        voltageV = 13.28,
        currentA = -0.08,
        powerW = -1.0624,
        charging = false,
        socPercent = 89.177,
        remainingAh = 160.156,
        capacityAh = 180.0,
    )

    private fun state(phase: CollectionPhase, error: String? = null) =
        CollectionState(phase = phase, visibility = CollectionVisibility.entries.first(), error = error)

    @Test
    fun collectingShowsDirectionSocAndValues() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), discharging, NOW)

        assertEquals("Discharging · 89% · 160.2 / 180 Ah", content.title)
        assertEquals("13.28 V · 0.08 A · 1.1 W", content.text)
        assertEquals(1_000L, content.whenMs)
    }

    @Test
    fun chargingAboveIdleThresholdShowsCharging() {
        val charging = discharging.copy(currentA = 4.1, powerW = 54.4, charging = true)

        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), charging, NOW)

        assertEquals("Charging · 89% · 160.2 / 180 Ah", content.title)
        assertEquals("13.28 V · 4.10 A · 54.4 W", content.text)
    }

    @Test
    fun nearZeroCurrentShowsIdle() {
        val idle = discharging.copy(currentA = 0.01, powerW = 0.1)

        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), idle, NOW)

        assertEquals("Idle · 89% · 160.2 / 180 Ah", content.title)
    }

    @Test
    fun collectingWithoutSampleNeverShowsZeroSoc() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.COLLECTING), null, NOW)

        assertEquals("Collecting battery data", content.title)
        assertEquals("Waiting for the first full reading", content.text)
        assertNull(content.whenMs)
    }

    @Test
    fun notCollectingShowsStatusAndLastReading() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.WAITING_FOR_DEVICE), discharging, NOW)

        assertEquals("Meter is out of range", content.title)
        assertEquals("Last: 89% · 13.28 V", content.text)
    }

    @Test
    fun errorWithoutSampleShowsErrorAndNoReading() {
        val content = CollectionNotificationContent.from(state(CollectionPhase.ERROR, error = "GATT 133"), null, NOW)

        assertEquals("Collection paused: GATT 133", content.title)
        assertEquals("No reading yet", content.text)
        assertNull(content.whenMs)
    }

    @Test
    fun staleSampleWhileCollectingIsNotShownAsLive() {
        val content = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging,
            discharging.timestampMs + 61_000L,
        )

        assertEquals("Collecting battery data", content.title)
        assertEquals("Last: 89% · 13.28 V", content.text)
    }

    @Test
    fun fractionalCapacityKeepsOneDecimalAndMissingCapacityShowsRemainingOnly() {
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

        assertEquals("Discharging · 89% · 160.2 / 100.5 Ah", fractional.title)
        assertEquals("Discharging · 89% · 160.2 Ah", missing.title)
    }

    @Test
    fun socIsClampedToPercentRange() {
        val content = CollectionNotificationContent.from(
            state(CollectionPhase.COLLECTING),
            discharging.copy(socPercent = 104.0),
            NOW,
        )

        assertEquals("Discharging · 100% · 160.2 / 180 Ah", content.title)
    }

    private companion object {
        const val NOW = 2_000L
    }
}
