package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.protocol.FrameLogEntry
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.protocol.PacketDirection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardContentTest {
    @Test
    fun noDeviceStateShowsNoDeviceEmptyState() {
        val model = buildDashboardContent(BleUiState())

        assertEquals(DashboardEmptyState.NoDevice, model.emptyState)
        assertNull(model.hero)
        assertEquals(null, model.connection.deviceAddress)
    }

    @Test
    fun connectedWithoutAFrameShowsNoDataEmptyState() {
        val model = buildDashboardContent(
            BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
                hasBFrame = true,
                packetLog = listOf(inboundFrameLog(timestampMs = 42L, ascii = ":B=1226110,0,271158,\n")),
            )
        )

        assertEquals(DashboardEmptyState.NoData, model.emptyState)
        assertNull(model.hero)
        assertEquals("In progress", model.connection.bootstrapStatus)
        assertEquals(42L, model.connection.lastUpdateTimestampMs)
    }

    @Test
    fun disconnectedDeviceShowsDisconnectedEmptyState() {
        val model = buildDashboardContent(
            BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.DISCONNECTED,
                latestError = "signal lost",
            )
        )

        assertEquals(DashboardEmptyState.Disconnected, model.emptyState)
        assertNull(model.hero)
        assertEquals("signal lost", model.connection.detail)
    }

    @Test
    fun readyReadingBuildsDashboardCards() {
        val model = buildDashboardContent(
            BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
                hasAFrame = true,
                hasBFrame = true,
                latestReading = KmfReading(
                    voltageV = 13.26,
                    currentA = 1.08,
                    powerW = 14.32,
                    charging = true,
                    minutesRemaining = 95,
                    remainingAh = 57.93,
                    capacityAh = 100.0,
                    socPercent = 57.9,
                    chargeKwh = 12.34,
                    dischargeKwh = 7.89,
                ),
                packetLog = listOf(
                    inboundFrameLog(timestampMs = 1234L, ascii = ":A=1326,1080,0,5793,\n")
                ),
            )
        )

        assertNull(model.emptyState)
        assertEquals("58%", model.hero?.socText)
        assertEquals("Charging", model.hero?.statusText)
        assertEquals("13.26 V", model.hero?.headlineValue)
        assertEquals("57.93 Ah remaining of 100.00 Ah", model.hero?.supportingText)
        assertEquals("14.32 W", model.metrics.first { it.label == "Power" }.value)
        assertEquals("Ready", model.connection.connectionLabel)
        assertEquals("Ready", model.connection.bootstrapStatus)
        assertEquals(1234L, model.connection.lastUpdateTimestampMs)
        assertEquals("12.34 kWh", model.energyTotals.chargeTotal)
        assertEquals("7.89 kWh", model.energyTotals.dischargeTotal)
    }

    private fun testDevice(): ScannedDevice =
        ScannedDevice(
            name = "KMF271158",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            serviceUuids = listOf(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
        )

    private fun inboundFrameLog(
        timestampMs: Long,
        ascii: String,
    ): FrameLogEntry =
        FrameLogEntry(
            timestampMs = timestampMs,
            direction = PacketDirection.INBOUND,
            hex = "",
            ascii = ascii,
            length = ascii.length,
        )
}
