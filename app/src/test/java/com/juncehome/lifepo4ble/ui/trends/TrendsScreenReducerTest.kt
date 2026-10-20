package com.juncehome.lifepo4ble.ui.trends

import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportInfo
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExporter
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfCounterBucket
import com.juncehome.lifepo4ble.data.KmfHistorySummary
import com.juncehome.lifepo4ble.data.KmfSampleBucketRow
import com.juncehome.lifepo4ble.ui.components.EnergyText
import com.juncehome.lifepo4ble.ui.components.TrendChartColorMode
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsScreenReducerTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-18T10:07:00Z").toEpochMilli() // Sun 12:07 local
    private val minus = "−"
    private val dot = "·"

    private fun row(
        index: Long,
        atMs: Long,
        count: Int = 1,
        power: Triple<Double, Double, Double> = Triple(10.0, 10.0, 10.0),
    ) = KmfSampleBucketRow(
        bucket = index, sampleCount = count, firstMs = atMs, lastMs = atMs,
        avgPowerW = power.second, minPowerW = power.first, maxPowerW = power.third,
        avgSocPercent = 50.0, minSocPercent = 40.0, maxSocPercent = 60.0,
        avgVoltageV = 13.0, minVoltageV = 12.9, maxVoltageV = 13.1,
        avgCurrentA = 1.0, minCurrentA = 0.5, maxCurrentA = 1.5,
    )

    private fun sample(power: Double = -86.3) = KmfBatterySampleEntity(
        timestampMs = now, deviceAddress = "x", deviceName = null, voltageV = 13.25, currentA = -6.5,
        powerW = power, charging = false, minutesRemaining = 0, remainingAh = 0.0, capacityAh = 0.0,
        socPercent = 71.0, chargeKwh = 0.0, dischargeKwh = 0.0, hasAFrame = true, hasBFrame = true,
        hasCFrame = false, rawAFieldsCsv = null, rawBFieldsCsv = null, rawCFieldsCsv = null,
        connectionState = "", serviceUuid = null, notifyUuid = null, writeUuid = null,
    )

    private fun data(
        buckets: List<KmfSampleBucketRow> = emptyList(),
        counters: List<KmfCounterBucket> = emptyList(),
        latest: KmfBatterySampleEntity? = null,
        count: Long = 0,
        earliest: Long? = null,
        dbSizeBytes: Long = 0,
    ) = TrendsData(now, buckets, latest, counters, KmfHistorySummary(count, earliest, dbSizeBytes))

    private fun reduce(
        range: TrendsRange = TrendsRange.Day,
        metric: TrendsMetric = TrendsMetric.Power,
        data: TrendsData? = data(),
        lastExport: KmfBatteryHistoryExportInfo? = null,
        status: TrendsExportStatus = TrendsExportStatus.Idle,
        scrub: Int? = null,
    ) = TrendsScreenReducer.reduce(range, metric, data, lastExport, status, scrub, zone)

    @Test
    fun liveTicksAndEnergySlots() {
        val state = reduce(TrendsRange.Live)
        assertEquals(listOf("${minus}15 min", "${minus}10", "${minus}5", "now"), state.tickLabels)
        assertEquals("now", state.nowLabel)
        assertTrue(state.isLive)
        assertEquals("today by hour", state.energy.title)
        assertEquals("00:00", state.energy.fromLabel)
        assertEquals("now", state.energy.toLabel)
        assertEquals(13, state.energy.bars.size)
    }

    @Test
    fun dayTicksAndEnergySlots() {
        val state = reduce(TrendsRange.Day)
        assertEquals(listOf("12:07", "18:07", "00:07", "06:07", "now"), state.tickLabels)
        assertEquals("latest", state.nowLabel)
        assertEquals("last 24 h by hour", state.energy.title)
        assertEquals("13:00", state.energy.fromLabel)
        assertEquals(24, state.energy.bars.size)
    }

    @Test
    fun weekTicksAndEnergySlots() {
        val state = reduce(TrendsRange.Week)
        assertEquals(listOf("Sun", "Tue", "Thu", "Sat"), state.tickLabels)
        assertEquals("last 7 days", state.energy.title)
        assertEquals("Mon", state.energy.fromLabel)
        assertEquals(7, state.energy.bars.size)
    }

    @Test
    fun monthTicksAndEnergySlots() {
        val state = reduce(TrendsRange.Month)
        assertEquals(listOf("18 Sep", "28 Sep", "8 Oct", "18 Oct"), state.tickLabels)
        assertEquals("last 30 days", state.energy.title)
        assertEquals("19 Sep", state.energy.fromLabel)
        assertEquals(30, state.energy.bars.size)
    }

    @Test
    fun energyReadStartIsOneHourBeforeFirstSlot() {
        val first = TrendsEnergySlots.slotStarts(TrendsRange.Day, now, zone).first()
        assertEquals(first - 3_600_000L, TrendsEnergySlots.energyReadStartMs(TrendsRange.Day, now, zone))
    }

    @Test
    fun energyTotalsBarsAndNet() {
        val slotStart = TrendsEnergySlots.slotStarts(TrendsRange.Day, now, zone).last()
        val counters = listOf(
            KmfCounterBucket(slotStart / TrendsEnergyMath.FINE_BUCKET_MS, slotStart, 1.0, 5.0, slotStart + 1000, 1.5, 5.25),
        )
        val state = reduce(TrendsRange.Day, data = data(counters = counters))
        assertTrue(state.energy.hasData)
        assertEquals(EnergyText("500", "Wh"), state.energy.inEnergy)
        assertEquals(EnergyText("250", "Wh"), state.energy.outEnergy)
        assertEquals(EnergyText("+250", "Wh"), state.energy.netEnergy)
        assertEquals(EnergyBar(1f, 0.5f), state.energy.bars.last())
        assertEquals(EnergyBar(0f, 0f), state.energy.bars.first())
    }

    @Test
    fun negativeNetUsesTrueMinus() {
        val slotStart = TrendsEnergySlots.slotStarts(TrendsRange.Day, now, zone).last()
        val counters = listOf(
            KmfCounterBucket(slotStart / TrendsEnergyMath.FINE_BUCKET_MS, slotStart, 1.0, 5.0, slotStart + 1000, 1.1, 5.4),
        )
        assertEquals(EnergyText("${minus}300", "Wh"), reduce(data = data(counters = counters)).energy.netEnergy)
    }

    @Test
    fun statsUseMinMaxAndWeightedAverage() {
        val rows = listOf(
            row(1, now - 2000, count = 1, power = Triple(-20.0, 0.0, 10.0)),
            row(2, now - 1000, count = 3, power = Triple(5.0, 40.0, 80.0)),
        )
        val state = reduce(data = data(buckets = rows))
        assertEquals("${minus}20 W", state.minText)
        assertEquals("+30 W", state.avgText)
        assertEquals("+80 W", state.maxText)
    }

    @Test
    fun signedMetricsUseSymmetricRangeAndSignedColors() {
        val rows = listOf(
            row(1, now - 2000, power = Triple(-50.0, -50.0, -50.0)),
            row(2, now - 1000, power = Triple(20.0, 20.0, 20.0)),
        )
        val state = reduce(data = data(buckets = rows))
        assertEquals(TrendChartColorMode.Signed, state.colorMode)
        assertEquals(-50.0, state.yMin, 0.0)
        assertEquals(50.0, state.yMax, 0.0)
    }

    @Test
    fun emptySignedRangeStillHasPositiveSpan() {
        val state = reduce()
        assertTrue(state.yMax - state.yMin > 0.0)
    }

    @Test
    fun singleMetricPadsRangeAndHandlesFlatData() {
        val varied = reduce(metric = TrendsMetric.Soc, data = data(buckets = listOf(
            row(1, now - 2000).copy(avgSocPercent = 40.0), row(2, now - 1000).copy(avgSocPercent = 60.0),
        )))
        assertEquals(TrendChartColorMode.Single, varied.colorMode)
        assertEquals(37.0, varied.yMin, 1e-9)
        assertEquals(63.0, varied.yMax, 1e-9)
        val flat = reduce(metric = TrendsMetric.Voltage, data = data(buckets = listOf(row(1, now - 1000))))
        assertEquals(12.0, flat.yMin, 1e-9)
        assertEquals(14.0, flat.yMax, 1e-9)
    }

    @Test
    fun gapStartsNewSegment() {
        val bucketMs = TrendsRange.Day.bucketMs
        val rows = listOf(
            row(1, now - 60 * 60_000L),
            row(2, now - 60 * 60_000L + bucketMs),
            row(3, now - 10 * 60_000L),
        )
        val points = reduce(data = data(buckets = rows)).points
        assertEquals(listOf(false, false, true), points.map { it.startsSegment })
        assertTrue(points[0].xFraction < points[2].xFraction)
        assertTrue(points[2].xFraction in 0f..1f)
    }

    @Test
    fun scrubTooltipClampsAndFormats() {
        val rows = listOf(row(1, now - 2000, power = Triple(5.0, 5.0, 5.0)), row(2, now - 1000, power = Triple(-7.0, -7.0, -7.0)))
        val high = reduce(data = data(buckets = rows), scrub = 99)
        assertEquals(1, high.scrubIndex)
        assertEquals("Sun 12:06 $dot ${minus}7 W", high.tooltip)
        val low = reduce(data = data(buckets = rows), scrub = -4)
        assertEquals(0, low.scrubIndex)
        assertEquals("Sun 12:06 $dot +5 W", low.tooltip)
        assertEquals("12:06 $dot +5 W", reduce(TrendsRange.Live, data = data(buckets = rows), scrub = 0).tooltip)
        assertEquals("18 Oct $dot +5 W", reduce(TrendsRange.Month, data = data(buckets = rows), scrub = 0).tooltip)
        assertNull(reduce(data = data(buckets = rows)).tooltip)
    }

    @Test
    fun nowValueFormatsWithoutUnit() {
        assertEquals("${minus}86", reduce(data = data(latest = sample())).nowValue)
        assertEquals("13.25", reduce(metric = TrendsMetric.Voltage, data = data(latest = sample())).nowValue)
        assertEquals("${minus}6.5", reduce(metric = TrendsMetric.Current, data = data(latest = sample())).nowValue)
        assertEquals("W", reduce().unit)
    }

    @Test
    fun emptyStateShowsPlaceholders() {
        val state = reduce()
        assertEquals("--", state.nowValue)
        assertEquals("--", state.minText)
        assertEquals("--", state.avgText)
        assertEquals("--", state.maxText)
        assertFalse(state.energy.hasData)
        assertEquals(EnergyText.MISSING, state.energy.inEnergy)
        assertEquals(EnergyText.MISSING, state.energy.netEnergy)
        assertTrue(state.energy.bars.all { it.upFraction == 0f && it.downFraction == 0f })
        assertEquals("No readings saved yet", state.summaryLine)
        assertEquals("0", state.countText)
        assertFalse(state.canClear)
        assertFalse(state.canExport)
        assertFalse(state.loading)
        assertTrue(reduce(data = null).loading)
    }

    @Test
    fun historySummaryAndActions() {
        val earliest = Instant.parse("2026-10-03T08:00:00Z").toEpochMilli()
        val state = reduce(data = data(count = 128_430, earliest = earliest))
        assertEquals("128,430", state.countText)
        assertEquals("128,430 readings since 3 Oct $dot kept until you clear them", state.summaryLine)
        assertTrue(state.canClear && state.canExport)
    }

    @Test
    fun summaryLineShowsDbSize() {
        val earliest = Instant.parse("2026-10-03T08:00:00Z").toEpochMilli()
        val withSize = reduce(data = data(count = 128_430, earliest = earliest, dbSizeBytes = 331_500_000))
        assertEquals("128,430 readings since 3 Oct $dot 331.5 MB $dot kept until you clear them", withSize.summaryLine)
        assertEquals("1 MB", TrendsScreenReducer.formatDbSize(1_000_000).replace(".0 MB", " MB"))
        assertEquals("315.0 MB", TrendsScreenReducer.formatDbSize(315_000_000))
        assertEquals("1.20 GB", TrendsScreenReducer.formatDbSize(1_200_000_000))
        assertEquals("1 KB", TrendsScreenReducer.formatDbSize(10))
    }

    @Test
    fun exportLinesAndStatus() {
        val populated = data(count = 5)
        assertEquals("No export yet", reduce().lastExportLine)
        val today = KmfBatteryHistoryExportInfo("battery-history.csv", Instant.parse("2026-10-18T07:30:00Z").toEpochMilli(), 10)
        assertEquals("Last export today 09:30 $dot battery-history.csv", reduce(lastExport = today).lastExportLine)
        val older = today.copy(modifiedMs = Instant.parse("2026-10-10T07:30:00Z").toEpochMilli())
        assertEquals("Last export 10 Oct 09:30 $dot battery-history.csv", reduce(lastExport = older).lastExportLine)

        val running = reduce(data = populated, status = TrendsExportStatus.Running(1200))
        assertEquals("Exporting… 1200 rows", running.exportButtonLabel)
        assertTrue(running.exportRunning)
        assertFalse(running.canExport)
        assertFalse(running.canClear)
        assertEquals("Export CSV", reduce().exportButtonLabel)
        assertEquals("Exported 42 readings", reduce(status = TrendsExportStatus.Done(42)).statusLine)
        assertEquals("Export failed: disk full", reduce(status = TrendsExportStatus.Failed("disk full")).statusLine)
        assertEquals("Saved history cleared", reduce(status = TrendsExportStatus.Cleared).statusLine)
        assertEquals("Clear failed: x", reduce(status = TrendsExportStatus.ClearFailed("x")).statusLine)
        assertNull(reduce().statusLine)
        assertEquals(KmfBatteryHistoryExporter.ADB_PULL_COMMAND, reduce().adbCommand)
    }

    @Test
    fun capacityMetricUsesRemainingAhAndShowsCapacitySuffix() {
        val latest = sample().copy(remainingAh = 164.0, capacityAh = 200.0)
        val rows = listOf(
            row(1, now - 2000).copy(avgRemainingAh = 160.0, minRemainingAh = 158.0, maxRemainingAh = 162.0),
            row(2, now - 1000).copy(avgRemainingAh = 164.0, minRemainingAh = 163.0, maxRemainingAh = 165.0),
        )
        val state = reduce(metric = TrendsMetric.Capacity, data = data(buckets = rows, latest = latest))
        assertEquals("164.0", state.nowValue)
        assertEquals("/ 200 Ah", state.nowUnitText)
        assertEquals("Capacity - latest", state.metricCaption)
        assertEquals("158.0 Ah", state.minText)
        assertEquals("165.0 Ah", state.maxText)
        assertEquals(listOf(160.0, 164.0), state.points.map { it.value })
        assertEquals("Ah", state.unit)
        val fractional = reduce(metric = TrendsMetric.Capacity, data = data(latest = latest.copy(capacityAh = 100.5)))
        assertEquals("/ 100.5 Ah", fractional.nowUnitText)
        val unknown = reduce(metric = TrendsMetric.Capacity, data = data(latest = latest.copy(capacityAh = 0.0)))
        assertEquals("Ah", unknown.nowUnitText)
    }

    @Test
    fun metricCaptionsAndNames() {
        assertEquals("Power - now", reduce(TrendsRange.Live).metricCaption)
        assertEquals("State of charge - latest", reduce(metric = TrendsMetric.Soc).metricCaption)
        assertEquals(
            listOf("W", "%", "V", "A", "Ah"),
            TrendsMetric.entries.map { it.unit },
        )
        assertEquals(
            listOf("Power", "State of charge", "Voltage", "Current", "Capacity"),
            TrendsMetric.entries.map { it.fullName },
        )
    }

    @Test
    fun yTicksAreNiceAndSpanTheRange() {
        val rows = listOf(row(1, now - 2000, power = Triple(-80.0, -80.0, -80.0)), row(2, now - 1000, power = Triple(60.0, 60.0, 60.0)))
        val state = reduce(data = data(buckets = rows))
        assertEquals(listOf("50", "0", "50"), state.yTicks.map { it.label })
        assertTrue(reduce(data = data()).yTicks.isEmpty())
    }

    @Test
    fun stepperStateForShiftedWindows() {
        val earliest = now - 30L * 86_400_000L
        val base = data(count = 5, earliest = earliest)
        val atNow = reduce(TrendsRange.Hour6, data = base)
        assertEquals("Today 06:07 - 12:07", atNow.windowLabel)
        assertTrue(atNow.canEarlier)
        assertFalse(atNow.canLater)
        val shifted = TrendsScreenReducer.reduce(
            TrendsRange.Hour6, TrendsMetric.Power, base, null, TrendsExportStatus.Idle, null, zone, offset = 1,
        )
        assertEquals("Today 00:07 - 06:07", shifted.windowLabel)
        assertTrue(shifted.canLater)
        assertEquals("06:07", shifted.tickLabels.last())
        assertEquals("6 h by 30 min", shifted.energy.title)
        assertEquals("06:07", shifted.energy.toLabel)
        val live = reduce(TrendsRange.Live, data = base)
        assertFalse(live.canEarlier)
        assertFalse(live.canLater)
        assertEquals("Last 15 min", live.windowLabel)
        val oldest = TrendsScreenReducer.reduce(
            TrendsRange.Hour6, TrendsMetric.Power, data(count = 5, earliest = now - 3_600_000L), null,
            TrendsExportStatus.Idle, null, zone,
        )
        assertFalse(oldest.canEarlier)
        assertFalse(reduce(TrendsRange.Hour6, data = data()).canEarlier)
    }

    @Test
    fun customRangeStateAndEnergyTitle() {
        val custom = TrendsCustomRange(now - 3 * 3_600_000L, now - 3_600_000L)
        val state = TrendsScreenReducer.reduce(
            TrendsRange.Custom, TrendsMetric.Power, data(count = 1, earliest = now - 86_400_000L), null,
            TrendsExportStatus.Idle, null, zone, custom = custom,
        )
        assertTrue(state.isCustom)
        assertEquals(custom.fromMs, state.customFromMs)
        assertEquals(custom.toMs, state.customToMs)
        assertEquals("selected period by 15 min", state.energy.title)
        assertFalse(state.canEarlier)
        assertFalse(state.canLater)
        assertEquals(now - 86_400_000L, state.earliestMs)
    }
}
