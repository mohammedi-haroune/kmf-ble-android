package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LivelinePowerChartModelTest {
    @Test
    fun payloadPreservesPointOrderAndLatestValue() {
        val chart = buildChart()

        val payload = chart.toLivelinePowerChartPayload(nativeSentAtMs = 1_234_567_890L)

        requireNotNull(payload)
        assertEquals(4, payload.points.size)
        assertEquals(listOf(1.0, 2.0, 3.0, 61.0), payload.points.map { it.time })
        assertEquals(listOf(10.5, 11.5, 12.5, 12.5), payload.points.map { it.value })
        assertEquals(12.5, payload.latestValue, 0.0000001)
        assertEquals(1_234_567_890L, payload.nativeSentAtMs)
    }

    @Test
    fun payloadSerializesToCompactJson() {
        val chart = buildChart()

        val json = requireNotNull(chart.toLivelinePowerChartPayload(nativeSentAtMs = 99L)).toJson()

        assertEquals(
            "{\"points\":[{\"time\":1.0,\"value\":10.5},{\"time\":2.0,\"value\":11.5},{\"time\":3.0,\"value\":12.5},{\"time\":61.0,\"value\":12.5}],\"latestValue\":12.5,\"nativeSentAtMs\":99}",
            json,
        )
    }

    @Test
    fun emptyRenderedPointsProduceNoPayload() {
        val chart = LiveChartModel(
            title = "Power",
            unit = "W",
            latestValue = "0 W",
            points = emptyList(),
            latestXSeconds = 0.0,
            visibleXMinSeconds = 0.0,
            visibleXMaxSeconds = 0.0,
            renderedXMinSeconds = 0.0,
            renderedXMaxSeconds = 60.0,
        )

        assertNull(chart.toLivelinePowerChartPayload())
    }

    @Test
    fun livelinePowerCssDoesNotClipChartShell() {
        val css = readLivelinePowerCss()

        assertFalse(Regex("""\.chart-shell\{[^}]*overflow:hidden""").containsMatchIn(css))
    }

    private fun buildChart(): LiveChartModel {
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
            ),
            samples = listOf(
                sample(timestampMs = 1_000L, powerW = 10.5),
                sample(timestampMs = 2_000L, powerW = 11.5),
                sample(timestampMs = 3_000L, powerW = 12.5),
            ),
            range = LiveRange.TenMinutes,
        )
        return content.charts.first { it.title == "Power" }
    }

    private fun readLivelinePowerCss(): String {
        val candidates = listOf(
            Path.of("src/main/assets/liveline-power/assets/index-IjHDVWO-.css"),
            Path.of("app/src/main/assets/liveline-power/assets/index-IjHDVWO-.css"),
        )
        return candidates
            .first(Files::exists)
            .let { path -> String(Files.readAllBytes(path), StandardCharsets.UTF_8) }
    }

    private fun testDevice(): ScannedDevice =
        ScannedDevice(
            name = "KMF271158",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            serviceUuids = listOf(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
        )

    private fun sample(
        timestampMs: Long,
        powerW: Double,
    ): KmfBatterySampleEntity =
        KmfBatterySampleEntity(
            timestampMs = timestampMs,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "KMF271158",
            voltageV = 13.2,
            currentA = 1.1,
            powerW = powerW,
            charging = true,
            minutesRemaining = 95,
            remainingAh = 57.93,
            capacityAh = 100.0,
            socPercent = 57.9,
            chargeKwh = 12.34,
            dischargeKwh = 7.89,
            hasAFrame = true,
            hasBFrame = true,
            hasCFrame = true,
            rawAFieldsCsv = null,
            rawBFieldsCsv = null,
            rawCFieldsCsv = null,
            connectionState = "READY",
            serviceUuid = null,
            notifyUuid = null,
            writeUuid = null,
        )
}
