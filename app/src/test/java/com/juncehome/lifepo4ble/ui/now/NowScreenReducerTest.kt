package com.juncehome.lifepo4ble.ui.now

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.components.StatusPillIndicator
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowScreenReducerTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    // 2025-10-09T14:32:00Z
    private val now = 1_760_020_320_000L

    @Test
    fun collectingWithPositiveCurrentIsChargingWithLivePulse() {
        val model = reduce(state(phase = CollectionPhase.COLLECTING, frameAgeMs = 2_000L))

        assertEquals(NowDisplayState.Charging, model.displayState)
        assertEquals("Live \u00b7 2s ago", model.pill.label)
        assertEquals(NowTone.Live, model.pill.tone)
        assertEquals(StatusPillIndicator.Pulse, model.pill.indicator)
        assertNull(model.banner)
        assertEquals("Charging", model.flow.label)
        assertEquals(NowTone.Charge, model.flow.tone)
        assertEquals(NowCellAnimation.ChargeWave, model.flow.animation)
        assertEquals("+164.7", model.flow.power)
        assertEquals("+12.40", model.flow.current)
        assertEquals(NowTone.Charge, model.barTone)
        assertFalse(model.dataLink.expanded)
    }

    @Test
    fun negativeCurrentIsDischargingWithTrueMinusAndDrainWave() {
        val model = reduce(state(phase = CollectionPhase.COLLECTING, currentA = -6.66, powerW = -86.3))

        assertEquals(NowDisplayState.Discharging, model.displayState)
        assertEquals("\u221286.3", model.flow.power)
        assertEquals("\u22126.66", model.flow.current)
        assertEquals(NowTone.Discharge, model.flow.tone)
        assertEquals(NowCellAnimation.DrainWave, model.flow.animation)
        assertEquals(NowTone.Discharge, model.barTone)
    }

    @Test
    fun zeroCurrentCountsAsChargingWithoutSign() {
        val model = reduce(state(phase = CollectionPhase.COLLECTING, currentA = 0.0, powerW = 0.0, minutes = 0))

        assertEquals(NowDisplayState.Charging, model.displayState)
        assertEquals("Charging", model.flow.label)
        assertEquals(NowTone.Charge, model.flow.tone)
        assertEquals(NowTone.Charge, model.barTone)
        assertEquals("0.0", model.flow.power)
        assertEquals("0.00", model.flow.current)
        assertEquals(NowCellAnimation.ChargeWave, model.flow.animation)
        assertEquals("Estimate unavailable", model.flow.eta)
    }

    @Test
    fun liveAgeThresholds() {
        assertEquals(StatusPillIndicator.Pulse, reduce(state(frameAgeMs = 5_000L)).pill.indicator)
        val steady = reduce(state(frameAgeMs = 5_001L))
        assertEquals(StatusPillIndicator.Steady, steady.pill.indicator)
        assertEquals(NowTone.Live, steady.pill.tone)
        val tenSeconds = reduce(state(frameAgeMs = 10_000L))
        assertEquals(NowDisplayState.Charging, tenSeconds.displayState)
        assertEquals("Live \u00b7 10s ago", tenSeconds.pill.label)
        val stale = reduce(state(frameAgeMs = 10_001L))
        assertEquals(NowDisplayState.Stale, stale.displayState)
    }

    @Test
    fun staleKeepsValuesExpandsLinkAndHasNoBanner() {
        val model = reduce(state(frameAgeMs = 15_000L))

        assertEquals(NowDisplayState.Stale, model.displayState)
        assertEquals("No data \u00b7 15s", model.pill.label)
        assertEquals(NowTone.Discharge, model.pill.tone)
        assertNull(model.banner)
        assertEquals("82", model.soc)
        assertEquals(NowCellAnimation.None, model.flow.animation)
        assertTrue(model.dataLink.expanded)
        assertEquals(NowLinkMode.Stale, model.dataLink.mode)
        assertEquals("Frames stopped 15s ago \u00b7 values may be outdated", model.dataLink.caption)
        assertEquals(NowTone.Discharge, model.dataLink.captionTone)
    }

    @Test
    fun lowBatteryBoundaryIsStrictlyBelowTwenty() {
        val at20 = reduce(state(soc = 20.0, currentA = -6.66, powerW = -86.3))
        assertEquals(NowDisplayState.Discharging, at20.displayState)
        assertFalse(at20.lowBattery)

        val below = reduce(state(soc = 19.9, currentA = -6.66, powerW = -86.3, minutes = 65))
        assertEquals(NowDisplayState.LowBattery, below.displayState)
        assertTrue(below.lowBattery)
        assertEquals(NowTone.Error, below.barTone)
        assertEquals("Below 20%, about 1h 05m left at this draw.", below.lowBatteryDetail)
    }

    @Test
    fun lowBatteryIgnoredWhenCapacityUnknown() {
        val model = reduce(state(soc = 0.0, capacityAh = 0.0))

        assertFalse(model.lowBattery)
        assertEquals("--", model.ahCapacity)
    }

    @Test
    fun lowBatteryKeptInOfflineStates() {
        val model = reduce(state(phase = CollectionPhase.WAITING_FOR_DEVICE, soc = 10.0, currentA = 3.0, powerW = 39.0))

        assertEquals(NowDisplayState.OutOfRange, model.displayState)
        assertTrue(model.lowBattery)
        assertEquals("Below 20%.", model.lowBatteryDetail)
    }

    @Test
    fun firstSyncWithoutSavedReadingShowsPlaceholders() {
        val model = reduce(
            state(phase = CollectionPhase.BOOTSTRAPPING, hasValues = false, hasA = true, frameTimes = listOf(now - 1_000L)),
        )

        assertEquals(NowDisplayState.FirstSync, model.displayState)
        assertEquals("Syncing", model.pill.label)
        assertEquals(NowTone.Charge, model.pill.tone)
        assertEquals(StatusPillIndicator.Blink, model.pill.indicator)
        assertEquals("--", model.soc)
        assertEquals("--.--", model.voltage)
        assertEquals("--", model.ahRemaining)
        assertEquals("--", model.flow.power)
        assertEquals("Waiting for readings", model.flow.label)
        assertEquals(NowTone.Muted, model.flow.tone)
        assertNull(model.chart)
        assertTrue(model.dataLink.expanded)
        assertEquals("1 frames", model.dataLink.stat)
        assertEquals("First connection \u00b7 receiving 1 of 2 data sets", model.dataLink.caption)
        assertEquals("--", model.totalsIn)
    }

    @Test
    fun firstSyncWhileConnectingSaysConnecting() {
        val model = reduce(state(phase = CollectionPhase.CONNECTING, hasValues = false, frameTimes = emptyList()))

        assertEquals(NowDisplayState.FirstSync, model.displayState)
        assertEquals("First connection \u00b7 connecting to the meter", model.dataLink.caption)
    }

    @Test
    fun resyncKeepsLastValuesAndRefreshesEstimate() {
        val model = reduce(state(phase = CollectionPhase.BOOTSTRAPPING, hasA = true))

        assertEquals(NowDisplayState.Resyncing, model.displayState)
        assertEquals("Syncing", model.pill.label)
        assertEquals("82", model.soc)
        assertEquals("Refreshing estimate\u2026", model.flow.eta)
        assertEquals(NowCellAnimation.None, model.flow.animation)
        assertEquals("Reconnected \u00b7 refreshing values (receiving 1 of 2 data sets)", model.dataLink.caption)
    }

    @Test
    fun configuredWithoutServiceStateYetIsSyncing() {
        val model = reduce(state(phase = null))

        assertEquals(NowDisplayState.Resyncing, model.displayState)
    }

    @Test
    fun reconnectingShowsCountdownAttemptAndRetryNow() {
        val droppedAt = now - 60_000L // 14:31
        val model = reduce(
            state(
                phase = CollectionPhase.ERROR,
                nextReconnectAtMs = now + 14_200L,
                attempt = 4,
                linkDroppedAtMs = droppedAt,
                frameAgeMs = 60_000L,
            ),
        )

        assertEquals(NowDisplayState.Reconnecting, model.displayState)
        assertEquals("Reconnecting \u00b7 15 s", model.pill.label)
        assertEquals(StatusPillIndicator.Blink, model.pill.indicator)
        assertEquals(NowTone.Discharge, model.pill.tone)
        assertEquals(NowBannerAction.RetryNow, model.pill.action)
        assertEquals(NowPillIcon.Retry, model.pill.icon)
        assertEquals("Reconnecting in 15 seconds, retry now", model.pill.contentDescription)
        assertNull(model.banner)
        assertEquals("Link dropped at 14:31 \u00b7 attempt 4", model.dataLink.statusNote)
        assertEquals("Charging", model.flow.label)
        assertEquals("Last reading 14:31", model.flow.eta)
        assertEquals(NowCellAnimation.None, model.flow.animation)
        assertEquals("82", model.soc)
        assertEquals("no link", model.dataLink.stat)
        assertFalse(model.dataLink.expanded)
    }

    @Test
    fun reconnectCountdownRoundsUpAndBottomsOutAtZero() {
        val collection = CollectionState(CollectionPhase.ERROR, CollectionVisibility.FOREGROUND, nextReconnectAtMs = now + 1_001L)
        assertEquals(2, NowScreenReducer.reconnectCountdownSeconds(collection, now))
        assertEquals(1, NowScreenReducer.reconnectCountdownSeconds(collection, now + 1L))
        assertEquals(0, NowScreenReducer.reconnectCountdownSeconds(collection, now + 1_001L))
        assertEquals(0, NowScreenReducer.reconnectCountdownSeconds(collection, now + 5_000L))

        val overdue = reduce(state(phase = CollectionPhase.ERROR, nextReconnectAtMs = now - 10L, attempt = 1))
        assertEquals("Reconnecting\u2026", overdue.pill.label)
    }

    @Test
    fun outOfRangeIsNeutralWithoutAction() {
        val model = reduce(state(phase = CollectionPhase.WAITING_FOR_DEVICE, frameAgeMs = 60_000L))

        assertEquals(NowDisplayState.OutOfRange, model.displayState)
        assertEquals("Out of range", model.pill.label)
        assertEquals(NowTone.Neutral, model.pill.tone)
        assertNull(model.pill.action)
        assertNull(model.banner)
        assertEquals("Reconnects by itself when it is back \u00b7 last 14:31", model.dataLink.statusNote)
        assertEquals(NowTone.Neutral, model.dataLink.statusNoteTone)
    }

    @Test
    fun errorWithoutScheduledRetryIsCollectionErrorWithReason() {
        val model = reduce(state(phase = CollectionPhase.ERROR, error = "Could not turn on meter notifications"))

        assertEquals(NowDisplayState.CollectionError, model.displayState)
        assertEquals("Paused \u00b7 Retry", model.pill.label)
        assertEquals(NowTone.Error, model.pill.tone)
        assertEquals(NowBannerAction.Retry, model.pill.action)
        assertEquals(NowPillIcon.Retry, model.pill.icon)
        assertNull(model.banner)
        assertEquals("Could not turn on meter notifications", model.dataLink.statusNote)
        assertEquals(NowTone.Error, model.dataLink.statusNoteTone)
    }

    @Test
    fun stoppedPhaseIsCollectionOffWithStart() {
        val model = reduce(state(phase = CollectionPhase.STOPPED, frameAgeMs = 60_000L))

        assertEquals(NowDisplayState.CollectionOff, model.displayState)
        assertEquals("Off \u00b7 Start", model.pill.label)
        assertEquals(NowTone.Neutral, model.pill.tone)
        assertEquals(NowBannerAction.Start, model.pill.action)
        assertEquals(NowPillIcon.Play, model.pill.icon)
        assertNull(model.banner)
        assertEquals("Stopped from the notification \u00b7 last 14:31", model.dataLink.statusNote)
        assertEquals("82", model.soc)
        assertEquals("Last reading 14:31", model.flow.eta)
    }

    @Test
    fun notConfiguredWithMeterIsCollectionOffEvenIfServiceStateLingers() {
        val model = reduce(state(phase = CollectionPhase.COLLECTING, configured = false))

        assertEquals(NowDisplayState.CollectionOff, model.displayState)
    }

    @Test
    fun notConfiguredWithoutMeterIsNoMeter() {
        val model = reduce(
            BleUiState(collectionConfigurationLoaded = true),
        )

        assertEquals(NowDisplayState.NoMeter, model.displayState)
        assertEquals(NowBannerAction.SetUp, model.banner?.action)
        assertEquals("KMF Meter", model.meterName)
    }

    @Test
    fun offlineStatesKeepValuesAndMarkChartGap() {
        val samples = samples(count = 10)
        listOf(
            state(phase = CollectionPhase.WAITING_FOR_DEVICE),
            state(phase = CollectionPhase.STOPPED),
            state(phase = CollectionPhase.ERROR),
            state(phase = CollectionPhase.ERROR, nextReconnectAtMs = now + 5_000L),
        ).forEach { input ->
            val model = NowScreenReducer.reduce(input, samples, now, utc)
            assertTrue(model.displayState.isOffline)
            assertEquals("82", model.soc)
            assertEquals("13.28", model.voltage)
            assertEquals(NowTone.Charge, model.flow.tone)
            assertTrue(requireNotNull(model.chart).gapMarker)
        }
        val live = NowScreenReducer.reduce(state(), samples, now, utc)
        assertFalse(requireNotNull(live.chart).gapMarker)
    }

    @Test
    fun valueFormatting() {
        val model = reduce(state(soc = 82.4, remainingAh = 164.0, capacityAh = 200.0, chargeKwh = 48.314, dischargeKwh = 45.07))

        assertEquals("82", model.soc)
        assertEquals(0.824f, model.socFraction, 0.0001f)
        assertEquals("13.28", model.voltage)
        assertEquals("164.0", model.ahRemaining)
        assertEquals("200", model.ahCapacity)
        assertEquals("48.31", model.totalsIn)
        assertEquals("45.07", model.totalsOut)
        assertEquals("150.5", reduce(state(capacityAh = 150.5)).ahCapacity)
        assertEquals("KMF-01", reduce(state(meterName = "KMF-01")).meterName)
    }

    @Test
    fun etaFormatting() {
        assertEquals("2h 54m \u00b7 17:26", NowScreenReducer.formatEta(174, now, utc))
        assertEquals("1h 05m \u00b7 15:37", NowScreenReducer.formatEta(65, now, utc))
        assertEquals("45m \u00b7 15:17", NowScreenReducer.formatEta(45, now, utc))
        assertEquals("2h 00m \u00b7 16:32", NowScreenReducer.formatEta(120, now, utc))
        assertNull(NowScreenReducer.formatEta(0, now, utc))
        assertNull(NowScreenReducer.formatEta(-5, now, utc))
    }

    @Test
    fun durationUsesTwoLevelsMax() {
        assertEquals("0m", NowScreenReducer.formatDuration(0))
        assertEquals("45m", NowScreenReducer.formatDuration(45))
        assertEquals("2h 54m", NowScreenReducer.formatDuration(174))
        assertEquals("4d 0h", NowScreenReducer.formatDuration(5793))
        assertEquals("2w 6d", NowScreenReducer.formatDuration(20 * 1_440))
        assertEquals("1w 0d", NowScreenReducer.formatDuration(7 * 1_440))
        assertEquals("1mo 5d", NowScreenReducer.formatDuration(35 * 1_440))
        assertEquals("2y 3mo", NowScreenReducer.formatDuration(2 * 525_600 + 3 * 43_200))

        assertEquals("2h 54m \u00b7 17:26", reduce(state(minutes = 174)).flow.eta)
        assertEquals("Estimate unavailable", reduce(state(minutes = 0)).flow.eta)
    }

    @Test
    fun signedFormatting() {
        assertEquals("+164.7", NowScreenReducer.formatSigned(164.66, 1))
        assertEquals("\u221286.3", NowScreenReducer.formatSigned(-86.3, 1))
        assertEquals("0.0", NowScreenReducer.formatSigned(-0.01, 1))
        assertEquals("+212", NowScreenReducer.formatSigned(211.6, 0))
    }

    @Test
    fun frameBucketsCountPerSecondWithNewestLast() {
        val buckets = NowScreenReducer.frameBuckets(
            listOf(now, now - 500L, now - 999L, now - 1_000L, now - 59_999L, now - 60_000L, now + 300L),
            now,
        )

        assertEquals(60, buckets.size)
        assertEquals(4, buckets[59]) // now, -500, -999, and the future frame clamped to now
        assertEquals(1, buckets[58]) // -1000
        assertEquals(1, buckets[0]) // -59999
        assertEquals(6, buckets.sum()) // -60000 is outside the window
    }

    @Test
    fun linkTicksMarkMissedOnlyAfterFirstFrame() {
        val frames = (0 until 20).filter { it != 5 }.map { now - it * 1_000L }
        val ticks = NowScreenReducer.linkTicks(frames, now, NowLinkMode.Live)

        assertEquals(60, ticks.size)
        assertEquals(NowLinkTick.Received, ticks[59])
        assertEquals(NowLinkTick.Missed, ticks[54]) // 5 s ago
        assertEquals(NowLinkTick.Received, ticks[40]) // earliest frame, 19 s ago
        assertEquals(NowLinkTick.Idle, ticks[39]) // before any history
        assertEquals(NowLinkTick.Idle, ticks[0])

        val syncTicks = NowScreenReducer.linkTicks(frames, now, NowLinkMode.Sync)
        assertEquals(NowLinkTick.Idle, syncTicks[54])
    }

    @Test
    fun liveLinkStatAndCompactTicks() {
        val frames = (0 until 60).filter { it != 17 && it != 41 }.map { now - it * 1_000L }
        val model = reduce(state(frameTimes = frames, frameAgeMs = 0L))

        assertEquals("58/60 \u00b7 steady", model.dataLink.stat)
        assertEquals(30, model.dataLink.compactTicks.size)
        assertEquals(model.dataLink.ticks.takeLast(30), model.dataLink.compactTicks)

        val gappy = (0 until 60).filter { it % 10 != 3 }.map { now - it * 1_000L }
        assertEquals("54/60 \u00b7 gaps", reduce(state(frameTimes = gappy, frameAgeMs = 0L)).dataLink.stat)
    }

    @Test
    fun chartUsesOnlyLastFifteenMinutesWithMinMaxMeta() {
        val samples = listOf(
            sample(now - 16 * 60_000L, 999.0),
            sample(now - 10 * 60_000L, -64.0),
            sample(now - 5 * 60_000L, 212.0),
            sample(now, 100.0),
        )
        val chart = requireNotNull(NowScreenReducer.chart(NowDisplayState.Charging, samples, now))

        assertEquals(3, chart.points.size)
        assertEquals("max +212 \u00b7 min \u221264 W", chart.meta)
        assertEquals(212.0, chart.scaleW, 0.0)
        assertEquals(1f, chart.points.last().xFraction, 0.0001f)
        assertEquals(100.0, chart.endPowerW, 0.0)
        assertNull(NowScreenReducer.chart(NowDisplayState.Charging, emptyList(), now))
        assertNull(NowScreenReducer.chart(NowDisplayState.FirstSync, samples, now))
    }

    @Test
    fun chartDownsamplesLongHistoryButKeepsLatestValue() {
        val samples = (0 until 900).map { sample(now - it * 1_000L, if (it == 0) -42.0 else 10.0) }
        val chart = requireNotNull(NowScreenReducer.chart(NowDisplayState.Discharging, samples, now))

        assertTrue(chart.points.size <= NowScreenReducer.CHART_MAX_POINTS)
        assertEquals(-42.0, chart.endPowerW, 0.0)
        assertTrue(chart.points.zipWithNext().all { (a, b) -> a.xFraction <= b.xFraction })
    }

    private fun reduce(input: BleUiState): NowScreenState =
        NowScreenReducer.reduce(input, emptyList(), now, utc)

    private fun state(
        phase: CollectionPhase? = CollectionPhase.COLLECTING,
        configured: Boolean = true,
        hasValues: Boolean = true,
        currentA: Double = 12.40,
        powerW: Double = 164.7,
        soc: Double = 82.0,
        remainingAh: Double = 164.0,
        capacityAh: Double = 200.0,
        minutes: Int = 174,
        chargeKwh: Double = 48.31,
        dischargeKwh: Double = 45.07,
        frameAgeMs: Long = 60_000L.takeIf { phase != CollectionPhase.COLLECTING } ?: 2_000L,
        frameTimes: List<Long>? = null,
        hasA: Boolean = false,
        hasB: Boolean = false,
        nextReconnectAtMs: Long? = null,
        attempt: Int = 0,
        linkDroppedAtMs: Long? = null,
        error: String? = null,
        meterName: String? = "KMF Meter",
    ): BleUiState {
        val lastFrame = now - frameAgeMs
        return BleUiState(
            selectedDevice = ScannedDevice(meterName, "00:11:22:33:44:55", 0, emptyList()),
            latestReading = if (hasValues) {
                KmfReading(
                    voltageV = 13.28,
                    currentA = currentA,
                    powerW = powerW,
                    charging = currentA > 0,
                    minutesRemaining = minutes,
                    remainingAh = remainingAh,
                    capacityAh = capacityAh,
                    socPercent = soc,
                    chargeKwh = chargeKwh,
                    dischargeKwh = dischargeKwh,
                )
            } else {
                KmfReading()
            },
            lastReadingTimestampMs = if (hasValues) lastFrame else null,
            inboundFrameTimesMs = frameTimes ?: listOf(lastFrame),
            hasAFrame = hasA,
            hasBFrame = hasB,
            autonomousCollectionConfigured = configured,
            collectionConfigurationLoaded = true,
            collectionState = phase?.let {
                CollectionState(
                    phase = it,
                    visibility = CollectionVisibility.FOREGROUND,
                    nextReconnectAtMs = nextReconnectAtMs,
                    reconnectAttempt = attempt,
                    linkDroppedAtMs = linkDroppedAtMs,
                    error = error,
                )
            },
        )
    }

    private fun samples(count: Int): List<KmfBatterySampleEntity> =
        (0 until count).map { sample(now - 60_000L - it * 10_000L, 50.0 + it) }

    private fun sample(timestampMs: Long, powerW: Double) = KmfBatterySampleEntity(
        timestampMs = timestampMs,
        deviceAddress = "00:11:22:33:44:55",
        deviceName = "KMF Meter",
        voltageV = 13.28,
        currentA = powerW / 13.28,
        powerW = powerW,
        charging = powerW > 0,
        minutesRemaining = 0,
        remainingAh = 0.0,
        capacityAh = 200.0,
        socPercent = 82.0,
        chargeKwh = 0.0,
        dischargeKwh = 0.0,
        hasAFrame = true,
        hasBFrame = true,
        hasCFrame = false,
        rawAFieldsCsv = null,
        rawBFieldsCsv = null,
        rawCFieldsCsv = null,
        connectionState = "COLLECTING",
        serviceUuid = null,
        notifyUuid = null,
        writeUuid = null,
    )
}
