package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContentTest {
    @Test
    fun noDeviceShowsNoDeviceEmptyState() {
        val content = buildLiveContent(
            state = BleUiState(),
            samples = emptyList(),
            range = LiveRange.TenMinutes,
        )

        assertEquals(LiveEmptyState.NoDevice, content.emptyState)
        assertEquals(emptyList<LiveChartModel>(), content.charts)
        assertNull(content.deviceName)
    }

    @Test
    fun samplesAreFilteredToSelectedRangeAndSortedOldestFirst() {
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
            ),
            samples = listOf(
                sample(timestampMs = 3_600_000L, socPercent = 59.0, powerW = 120.0, currentA = 9.2, voltageV = 13.3),
                sample(timestampMs = 3_000_000L, socPercent = 58.0, powerW = 118.0, currentA = 9.0, voltageV = 13.2),
                sample(timestampMs = 1_700_000L, socPercent = 40.0, powerW = 75.0, currentA = 6.0, voltageV = 12.5),
            ),
            range = LiveRange.TenMinutes,
        )

        assertNull(content.emptyState)
        assertEquals("KMF271158", content.deviceName)
        assertEquals(4, content.charts.size)

        val socChart = content.charts.first { it.title == "State of charge" }
        assertEquals("59%", socChart.latestValue)
        assertEquals(listOf(0.0, 10.0), socChart.points.map { it.xMinutes })
        assertEquals(listOf(58.0, 59.0), socChart.points.map { it.y })

        val voltageChart = content.charts.first { it.title == "Voltage" }
        assertEquals("13.30 V", voltageChart.latestValue)
    }

    @Test
    fun samplesAreGroupedToOnePointPerSecondUsingLatestSampleInThatSecond() {
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
            ),
            samples = listOf(
                sample(timestampMs = 1_000L, socPercent = 58.0, voltageV = 13.1),
                sample(timestampMs = 1_150L, socPercent = 59.0, voltageV = 13.2),
                sample(timestampMs = 1_900L, socPercent = 60.0, voltageV = 13.3),
                sample(timestampMs = 2_000L, socPercent = 61.0, voltageV = 13.4),
                sample(timestampMs = 2_500L, socPercent = 62.0, voltageV = 13.5),
            ),
            range = LiveRange.TenMinutes,
        )

        val socChart = content.charts.first { it.title == "State of charge" }
        assertEquals(listOf(1_900L, 2_500L), socChart.points.map { it.timestampMs })
        assertEquals(listOf(60.0, 62.0), socChart.points.map { it.y })
        assertEquals(listOf(0.0, 1.0), socChart.points.map { it.xSeconds })

        val voltageChart = content.charts.first { it.title == "Voltage" }
        assertEquals("13.50 V", voltageChart.latestValue)
        assertEquals(listOf(13.3, 13.5), voltageChart.points.map { it.y })
    }

    @Test
    fun liveViewportShowsLatestWindowAtEnd() {
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
            ),
            samples = (0L..75L).map { second ->
                sample(timestampMs = second * 1_000L, socPercent = second.toDouble())
            },
            range = LiveRange.TenMinutes,
        )

        val socChart = content.charts.first { it.title == "State of charge" }
        assertEquals(75.0, socChart.latestXSeconds, 0.0000001)
        assertEquals(17.0, socChart.visibleXMinSeconds, 0.0000001)
        assertEquals(75.0, socChart.visibleXMaxSeconds, 0.0000001)
        assertEquals(0.0, socChart.renderedXMinSeconds, 0.0000001)
        assertEquals(60.0, socChart.renderedXMaxSeconds, 0.0000001)
        assertEquals((17L..75L).toList(), socChart.visiblePoints().map { it.timestampMs / 1_000L })
        assertEquals(0.0, socChart.renderedPoints().first().xSeconds, 0.0000001)
        assertEquals(58.0, socChart.renderedPoints().dropLast(1).last().xSeconds, 0.0000001)
        assertEquals(75L, socChart.renderedPoints().dropLast(1).last().timestampMs / 1_000L)
        assertEquals(60.0, socChart.renderedPoints().last().xSeconds, 0.0000001)
        assertEquals(77L, socChart.renderedPoints().last().timestampMs / 1_000L)
        assertEquals(
            socChart.renderedPoints().dropLast(1).last().y,
            socChart.renderedPoints().last().y,
            0.0000001,
        )
    }

    @Test
    fun chartXValuesStayWithinVicoPrecisionLimit() {
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
            ),
            samples = listOf(
                sample(timestampMs = 1_000_000L, socPercent = 58.0),
                sample(timestampMs = 1_002_000L, socPercent = 59.0),
                sample(timestampMs = 1_004_000L, socPercent = 60.0),
            ),
            range = LiveRange.TenMinutes,
        )

        val socChart = content.charts.first { it.title == "State of charge" }
        assertTrue(socChart.points.all { point -> BigDecimal.valueOf(point.xMinutes).scale() <= 4 })
        assertEquals(0.0333, socChart.points[1].xMinutes, 0.0000001)
        assertEquals(0.0667, socChart.points[2].xMinutes, 0.0000001)
    }

    @Test
    fun chartPointsKeepTimestampsAndXAxisLabelsShowClockTime() {
        val firstTimestampMs = 3_600_000L
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.READY,
            ),
            samples = listOf(
                sample(timestampMs = firstTimestampMs, socPercent = 58.0),
                sample(timestampMs = firstTimestampMs + 2_000L, socPercent = 59.0),
                sample(timestampMs = firstTimestampMs + 4_000L, socPercent = 60.0),
            ),
            range = LiveRange.TenMinutes,
        )

        val socChart = content.charts.first { it.title == "State of charge" }
        assertEquals(
            listOf(firstTimestampMs, firstTimestampMs + 2_000L, firstTimestampMs + 4_000L),
            socChart.points.map { it.timestampMs },
        )
        assertEquals(
            firstTimestampMs.toExpectedAxisTime(),
            formatLiveXAxisLabel(value = socChart.points.first().xSeconds, firstTimestampMs = firstTimestampMs),
        )
        assertEquals(
            (firstTimestampMs + 2_000L).toExpectedAxisTime(),
            formatLiveXAxisLabel(value = socChart.points[1].xSeconds, firstTimestampMs = firstTimestampMs),
        )
        assertNotEquals(
            "0s",
            formatLiveXAxisLabel(value = socChart.points[1].xSeconds, firstTimestampMs = firstTimestampMs),
        )
    }

    @Test
    fun powerValuesUseTieredRoundingForLiveCharts() {
        assertEquals("0.12 W", 0.123.toLivePowerText())
        assertEquals("5.00 W", 5.0.toLivePowerText())
        assertEquals("5.0 W", 5.01.toLivePowerText())
        assertEquals("12.3 W", 12.34.toLivePowerText())
        assertEquals("20.0 W", 20.0.toLivePowerText())
        assertEquals("21 W", 20.6.toLivePowerText())
        assertEquals("-14.3 W", (-14.32).toLivePowerText())
        assertEquals("-- W", Double.NaN.toLivePowerText())
    }

    @Test
    fun emptyRangeShowsNoSamplesState() {
        val content = buildLiveContent(
            state = BleUiState(
                selectedDevice = testDevice(),
                connectionState = ConnectionState.DISCONNECTED,
            ),
            samples = listOf(sample(timestampMs = 100_000L, deviceAddress = "11:22:33:44:55:66")),
            range = LiveRange.TenMinutes,
        )

        assertEquals(LiveEmptyState.NoSamples, content.emptyState)
        assertEquals(emptyList<LiveChartModel>(), content.charts)
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
        deviceAddress: String = "AA:BB:CC:DD:EE:FF",
        socPercent: Double = 57.9,
        powerW: Double = 14.32,
        currentA: Double = 1.08,
        voltageV: Double = 13.26,
    ): KmfBatterySampleEntity =
        KmfBatterySampleEntity(
            timestampMs = timestampMs,
            deviceAddress = deviceAddress,
            deviceName = "KMF271158",
            voltageV = voltageV,
            currentA = currentA,
            powerW = powerW,
            charging = true,
            minutesRemaining = 95,
            remainingAh = 57.93,
            capacityAh = 100.0,
            socPercent = socPercent,
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

    private fun Long.toExpectedAxisTime(): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(
            Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
        )
}
