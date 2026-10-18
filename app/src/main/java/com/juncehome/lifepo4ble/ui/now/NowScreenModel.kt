package com.juncehome.lifepo4ble.ui.now

import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.ConnectionState
import com.juncehome.lifepo4ble.ui.components.BannerTone
import com.juncehome.lifepo4ble.ui.components.KmfNumberText
import com.juncehome.lifepo4ble.ui.components.StatusPillIndicator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One display state per row of docs/design/README.md "Now tab states". */
enum class NowDisplayState {
    Charging,
    /** Zero current counts as Charging (product decision: no separate Idle/Full state). */
    Discharging,
    LowBattery,
    FirstSync,
    Resyncing,
    Stale,
    Reconnecting,
    OutOfRange,
    CollectionError,
    CollectionOff,

    /** No meter saved yet (first-run flow will normally cover this). */
    NoMeter,
    ;

    val isOffline: Boolean
        get() = this == Reconnecting || this == OutOfRange || this == CollectionError ||
            this == CollectionOff || this == NoMeter

    val isSyncing: Boolean
        get() = this == FirstSync || this == Resyncing
}

/** Semantic color role; the Compose layer maps it onto `KmfTheme.colors`. */
enum class NowTone { Charge, Discharge, Live, Error, Neutral, Muted }

enum class NowBannerAction(val label: String) {
    RetryNow("Retry now"),
    Retry("Retry"),
    Start("Start"),
    SetUp("Set up"),
}

enum class NowCellAnimation { ChargeWave, DrainWave, None }

enum class NowLinkTick { Received, Missed, Idle }

/** How the Data link ticks are colored. */
enum class NowLinkMode { Live, Stale, Sync, Offline }

data class NowPill(
    val label: String,
    val tone: NowTone,
    val indicator: StatusPillIndicator,
)

data class NowBanner(
    val title: String,
    val body: String?,
    val tone: BannerTone,
    val action: NowBannerAction?,
)

data class NowFlow(
    val label: String,
    val eta: String,
    val power: String,
    val current: String,
    val tone: NowTone,
    val animation: NowCellAnimation,
)

data class NowChartPoint(
    /** 0 = start of the 15 min window, 1 = now. */
    val xFraction: Float,
    val powerW: Double,
)

data class NowChart(
    val points: List<NowChartPoint>,
    /** Symmetric vertical scale in W (zero line sits in the middle). */
    val scaleW: Double,
    val meta: String,
    /** Red dashed marker at the last point (data stopped). */
    val gapMarker: Boolean,
) {
    val endPowerW: Double get() = points.lastOrNull()?.powerW ?: 0.0
}

data class NowDataLink(
    val expanded: Boolean,
    val mode: NowLinkMode,
    /** 60 one-second buckets, oldest first. */
    val ticks: List<NowLinkTick>,
    val stat: String,
    val caption: String?,
    val captionTone: NowTone,
) {
    val compactTicks: List<NowLinkTick> get() = ticks.takeLast(NowScreenReducer.COMPACT_TICKS)
}

data class NowScreenState(
    val displayState: NowDisplayState,
    val meterName: String,
    val pill: NowPill,
    val banner: NowBanner?,
    val hasValues: Boolean,
    val soc: String,
    /** 0..1 fill for the 10-segment bar. */
    val socFraction: Float,
    val voltage: String,
    val ahRemaining: String,
    val ahCapacity: String,
    /** Accent for the battery bar (flow color, red when low). */
    val barTone: NowTone,
    val lowBattery: Boolean,
    val lowBatteryDetail: String?,
    val flow: NowFlow,
    /** Null shows the "Chart starts with the first readings" placeholder. */
    val chart: NowChart?,
    val dataLink: NowDataLink,
    val totalsIn: String,
    val totalsOut: String,
)

/**
 * Pure mapping from [BleUiState] (+ collection state inside it, recent samples and the current
 * time) to the Now tab. No Compose so every rule is JVM-testable.
 */
object NowScreenReducer {
    const val LIVE_PULSE_MAX_AGE_MS = 5_000L
    const val STALE_AFTER_MS = 10_000L
    const val LOW_BATTERY_SOC = 20.0
    const val CHART_WINDOW_MS = 15 * 60_000L
    const val CHART_MAX_POINTS = 180
    const val LINK_SECONDS = 60
    const val COMPACT_TICKS = 30
    const val STEADY_MAX_MISSED = 3
    const val DEFAULT_METER_NAME = "KMF Meter"

    private const val DOT = "\u00b7"
    private const val ELLIPSIS = "\u2026"
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Largest unit first; the first step that fits sets the top level and the next step sets the second level. */
    private data class DurationStep(val label: String, val minutes: Int, val subLabel: String, val subMinutes: Int)

    private val DURATION_STEPS = listOf(
        DurationStep("y", 525_600, "mo", 43_200),
        DurationStep("mo", 43_200, "d", 1_440),
        DurationStep("w", 10_080, "d", 1_440),
        DurationStep("d", 1_440, "h", 60),
        DurationStep("h", 60, "m", 1),
    )

    fun reduce(
        state: BleUiState,
        samples: List<KmfBatterySampleEntity>,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): NowScreenState {
        val collection = state.collectionState
        val reading = state.latestReading
        val hasValues = state.lastReadingTimestampMs != null
        val lastFrameMs = lastFrameMs(state)
        val frameAgeMs = lastFrameMs?.let { (nowMs - it).coerceAtLeast(0L) }
        val displayState = resolveDisplayState(state, collection, hasValues, frameAgeMs)
        val flowTone = flowTone(reading)
        val lowBattery = isLowBattery(reading, hasValues)
        val lastClock = state.lastReadingTimestampMs?.let { formatClock(it, zoneId) }

        return NowScreenState(
            displayState = displayState,
            meterName = state.selectedDevice?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_METER_NAME,
            pill = pill(displayState, frameAgeMs),
            banner = banner(displayState, collection, nowMs, zoneId, lastClock),
            hasValues = hasValues,
            soc = if (hasValues) formatNumber(reading.socPercent, 0) else KmfNumberText.MISSING,
            socFraction = if (hasValues) (reading.socPercent / 100.0).coerceIn(0.0, 1.0).toFloat() else 0f,
            voltage = if (hasValues) formatNumber(reading.voltageV, 2) else "--.--",
            ahRemaining = if (hasValues) formatNumber(reading.remainingAh, 1) else KmfNumberText.MISSING,
            ahCapacity = formatCapacity(reading.capacityAh, hasValues),
            barTone = when {
                lowBattery -> NowTone.Error
                !hasValues -> NowTone.Charge
                else -> flowTone
            },
            lowBattery = lowBattery,
            lowBatteryDetail = if (lowBattery) lowBatteryDetail(reading) else null,
            flow = flow(displayState, reading, hasValues, nowMs, zoneId, lastClock),
            chart = chart(displayState, samples, nowMs),
            dataLink = dataLink(displayState, state, nowMs, frameAgeMs),
            totalsIn = if (hasValues) formatNumber(reading.chargeKwh, 2) else KmfNumberText.MISSING,
            totalsOut = if (hasValues) formatNumber(reading.dischargeKwh, 2) else KmfNumberText.MISSING,
        )
    }

    /** Newest of the live frame ring buffer and the last saved/merged reading time. */
    fun lastFrameMs(state: BleUiState): Long? {
        val lastFrame = state.inboundFrameTimesMs.maxOrNull()
        val lastReading = state.lastReadingTimestampMs
        return when {
            lastFrame == null -> lastReading
            lastReading == null -> lastFrame
            else -> maxOf(lastFrame, lastReading)
        }
    }

    fun resolveDisplayState(
        state: BleUiState,
        collection: CollectionState?,
        hasValues: Boolean,
        frameAgeMs: Long?,
    ): NowDisplayState {
        val syncState = if (hasValues) NowDisplayState.Resyncing else NowDisplayState.FirstSync
        val phase: CollectionPhase = when {
            state.autonomousCollectionConfigured -> collection?.phase ?: CollectionPhase.CONNECTING
            !state.collectionConfigurationLoaded && collection == null -> CollectionPhase.CONNECTING
            else -> return if (state.selectedDevice != null || hasValues) {
                NowDisplayState.CollectionOff
            } else {
                NowDisplayState.NoMeter
            }
        }
        return when (phase) {
            CollectionPhase.STOPPED -> NowDisplayState.CollectionOff
            CollectionPhase.WAITING_FOR_DEVICE -> NowDisplayState.OutOfRange
            CollectionPhase.ERROR -> if (collection?.nextReconnectAtMs != null) {
                NowDisplayState.Reconnecting
            } else {
                NowDisplayState.CollectionError
            }
            CollectionPhase.CONNECTING, CollectionPhase.BOOTSTRAPPING -> syncState
            CollectionPhase.COLLECTING -> when {
                frameAgeMs == null -> syncState
                frameAgeMs > STALE_AFTER_MS -> NowDisplayState.Stale
                isLowBattery(state.latestReading, hasValues) -> NowDisplayState.LowBattery
                state.latestReading.currentA < 0.0 -> NowDisplayState.Discharging
                else -> NowDisplayState.Charging
            }
        }
    }

    /** SoC is only trusted once the meter reported a capacity (A frame with capacity or a B-backed merge). */
    fun isLowBattery(reading: KmfReading, hasValues: Boolean): Boolean =
        hasValues && reading.capacityAh > 0.0 && reading.socPercent < LOW_BATTERY_SOC

    fun pill(displayState: NowDisplayState, frameAgeMs: Long?): NowPill {
        val ageSeconds = ((frameAgeMs ?: 0L) / 1_000L)
        return when (displayState) {
            NowDisplayState.Charging,
            NowDisplayState.Discharging,
            NowDisplayState.LowBattery -> NowPill(
                label = "Live $DOT ${ageSeconds}s ago",
                tone = NowTone.Live,
                indicator = if ((frameAgeMs ?: 0L) <= LIVE_PULSE_MAX_AGE_MS) {
                    StatusPillIndicator.Pulse
                } else {
                    StatusPillIndicator.Steady
                },
            )
            NowDisplayState.Stale -> NowPill("No data $DOT ${ageSeconds}s", NowTone.Discharge, StatusPillIndicator.Steady)
            NowDisplayState.FirstSync,
            NowDisplayState.Resyncing -> NowPill("Syncing", NowTone.Charge, StatusPillIndicator.Blink)
            NowDisplayState.Reconnecting -> NowPill("Reconnecting", NowTone.Discharge, StatusPillIndicator.Blink)
            NowDisplayState.OutOfRange -> NowPill("Out of range", NowTone.Neutral, StatusPillIndicator.Steady)
            NowDisplayState.CollectionError -> NowPill("Paused", NowTone.Error, StatusPillIndicator.Steady)
            NowDisplayState.CollectionOff -> NowPill("Off", NowTone.Neutral, StatusPillIndicator.Steady)
            NowDisplayState.NoMeter -> NowPill("No meter", NowTone.Neutral, StatusPillIndicator.Steady)
        }
    }

    /** Seconds until the scheduled retry, rounded up; 0 when due or unknown. */
    fun reconnectCountdownSeconds(collection: CollectionState?, nowMs: Long): Int {
        val at = collection?.nextReconnectAtMs ?: return 0
        val remaining = at - nowMs
        return if (remaining <= 0L) 0 else ceil(remaining / 1_000.0).toInt()
    }

    private fun banner(
        displayState: NowDisplayState,
        collection: CollectionState?,
        nowMs: Long,
        zoneId: ZoneId,
        lastClock: String?,
    ): NowBanner? {
        val lastSuffix = lastClock?.let { " $DOT last $it" }.orEmpty()
        return when (displayState) {
            NowDisplayState.Reconnecting -> {
                val seconds = reconnectCountdownSeconds(collection, nowMs)
                val dropped = (collection?.linkDroppedAtMs)?.let { formatClock(it, zoneId) } ?: lastClock
                val attempt = collection?.reconnectAttempt ?: 0
                val body = listOfNotNull(
                    dropped?.let { "Link dropped at $it" },
                    attempt.takeIf { it > 0 }?.let { "attempt $it" },
                ).joinToString(" $DOT ").ifEmpty { null }
                NowBanner(
                    title = if (seconds > 0) "Reconnecting in $seconds s" else "Reconnecting$ELLIPSIS",
                    body = body,
                    tone = BannerTone.Warning,
                    action = NowBannerAction.RetryNow,
                )
            }
            NowDisplayState.OutOfRange -> NowBanner(
                title = "Meter out of range",
                body = "Reconnects by itself when it is back$lastSuffix",
                tone = BannerTone.Neutral,
                action = null,
            )
            NowDisplayState.CollectionError -> NowBanner(
                title = "Collection paused",
                body = collection?.error?.takeIf { it.isNotBlank() } ?: "The meter connection failed",
                tone = BannerTone.Error,
                action = NowBannerAction.Retry,
            )
            NowDisplayState.CollectionOff -> NowBanner(
                title = "Background collection is off",
                body = "Start it to keep saving readings$lastSuffix",
                tone = BannerTone.Neutral,
                action = NowBannerAction.Start,
            )
            NowDisplayState.NoMeter -> NowBanner(
                title = "No meter set up",
                body = "Find your meter in Device to start collecting",
                tone = BannerTone.Neutral,
                action = NowBannerAction.SetUp,
            )
            else -> null
        }
    }

    private fun flowTone(reading: KmfReading): NowTone = when {
        reading.currentA < 0.0 -> NowTone.Discharge
        else -> NowTone.Charge
    }

    private fun directionLabel(reading: KmfReading): String = when {
        reading.currentA < 0.0 -> "Discharging"
        else -> "Charging"
    }

    private fun flow(
        displayState: NowDisplayState,
        reading: KmfReading,
        hasValues: Boolean,
        nowMs: Long,
        zoneId: ZoneId,
        lastClock: String?,
    ): NowFlow {
        if (!hasValues) {
            return NowFlow(
                label = if (displayState.isOffline) "No readings yet" else "Waiting for readings",
                eta = if (displayState.isOffline) "Values appear after the first sync" else "Usually takes a few seconds",
                power = KmfNumberText.MISSING,
                current = KmfNumberText.MISSING,
                tone = NowTone.Muted,
                animation = NowCellAnimation.None,
            )
        }
        val tone = flowTone(reading)
        val power = formatSigned(reading.powerW, 1)
        val current = formatSigned(reading.currentA, 2)
        val liveAnimation = when (tone) {
            NowTone.Charge -> NowCellAnimation.ChargeWave
            NowTone.Discharge -> NowCellAnimation.DrainWave
            else -> NowCellAnimation.None
        }
        val label: String
        val eta: String
        var animation = NowCellAnimation.None
        when {
            displayState.isOffline -> {
                label = directionLabel(reading) + (lastClock?.let { " at $it" }.orEmpty())
                eta = if (displayState == NowDisplayState.CollectionOff) "No new readings while off" else "Estimate paused"
            }
            displayState.isSyncing -> {
                label = directionLabel(reading)
                eta = "Refreshing estimate$ELLIPSIS"
            }
            else -> {
                label = directionLabel(reading)
                eta = formatEta(reading.minutesRemaining, nowMs, zoneId) ?: "Estimate unavailable"
                if (displayState != NowDisplayState.Stale) animation = liveAnimation
            }
        }
        return NowFlow(label, eta, power, current, tone, animation)
    }

    /** "2h 54m \u00b7 17:26" (hours only when non-zero; minutes zero-padded after hours). */
    fun formatEta(minutesRemaining: Int, nowMs: Long, zoneId: ZoneId): String? {
        if (minutesRemaining <= 0) return null
        val clock = formatClock(nowMs + minutesRemaining * 60_000L, zoneId)
        return "${formatDuration(minutesRemaining)} $DOT $clock"
    }

    /** Two levels max: "45m", "2h 54m", "4d 0h", "2w 6d", "1mo 5d", "2y 3mo". Months 30d, years 365d. */
    fun formatDuration(minutes: Int): String {
        val step = DURATION_STEPS.firstOrNull { minutes >= it.minutes } ?: return "${minutes}m"
        val top = minutes / step.minutes
        val sub = (minutes % step.minutes) / step.subMinutes
        val subText = if (step.subLabel == "m") String.format(Locale.US, "%02d", sub) else sub.toString()
        return "$top${step.label} $subText${step.subLabel}"
    }

    fun formatClock(epochMs: Long, zoneId: ZoneId): String =
        CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    private fun lowBatteryDetail(reading: KmfReading): String {
        val minutes = reading.minutesRemaining
        return if (reading.currentA < 0.0 && minutes > 0) {
            "Below 20%, about ${formatDuration(minutes)} left at this draw."
        } else {
            "Below 20%."
        }
    }

    fun chart(
        displayState: NowDisplayState,
        samples: List<KmfBatterySampleEntity>,
        nowMs: Long,
    ): NowChart? {
        if (displayState == NowDisplayState.FirstSync) return null
        val start = nowMs - CHART_WINDOW_MS
        val inWindow = samples
            .filter { it.timestampMs in start..nowMs }
            .sortedBy { it.timestampMs }
        if (inWindow.isEmpty()) return null
        val points = downsample(inWindow, start)
        val max = inWindow.maxOf { it.powerW }
        val min = inWindow.minOf { it.powerW }
        val scale = maxOf(abs(max), abs(min), 1.0)
        val meta = "max ${formatSigned(max, 0)} $DOT min ${formatSigned(min, 0)} W"
        return NowChart(
            points = points,
            scaleW = scale,
            meta = meta,
            gapMarker = displayState.isOffline,
        )
    }

    private fun downsample(sorted: List<KmfBatterySampleEntity>, windowStart: Long): List<NowChartPoint> {
        fun fraction(timestampMs: Long): Float =
            ((timestampMs - windowStart).toDouble() / CHART_WINDOW_MS).coerceIn(0.0, 1.0).toFloat()
        if (sorted.size <= CHART_MAX_POINTS) {
            return sorted.map { NowChartPoint(fraction(it.timestampMs), it.powerW) }
        }
        val bucketMs = CHART_WINDOW_MS.toDouble() / CHART_MAX_POINTS
        val buckets = sorted.groupBy { ((it.timestampMs - windowStart) / bucketMs).toInt().coerceAtMost(CHART_MAX_POINTS - 1) }
        val points = buckets.toSortedMap().map { (_, bucket) ->
            NowChartPoint(fraction(bucket.last().timestampMs), bucket.sumOf { it.powerW } / bucket.size)
        }
        // Keep the true last value so the end dot shows the latest reading.
        val last = sorted.last()
        return points.dropLast(1) + NowChartPoint(fraction(last.timestampMs), last.powerW)
    }

    /** Frames per second for the last [seconds] seconds, oldest first; the last bucket ends at [nowMs]. */
    fun frameBuckets(frameTimesMs: List<Long>, nowMs: Long, seconds: Int = LINK_SECONDS): IntArray {
        val buckets = IntArray(seconds)
        frameTimesMs.forEach { time ->
            val age = (nowMs - time).coerceAtLeast(0L)
            val index = seconds - 1 - (age / 1_000L).toInt()
            if (index in 0 until seconds) buckets[index] += 1
        }
        return buckets
    }

    /**
     * Turns buckets into ticks. Seconds before the first recorded frame are Idle (no history), as
     * are empty seconds while syncing; other empty seconds are Missed.
     */
    fun linkTicks(
        frameTimesMs: List<Long>,
        nowMs: Long,
        mode: NowLinkMode,
        seconds: Int = LINK_SECONDS,
    ): List<NowLinkTick> {
        val buckets = frameBuckets(frameTimesMs, nowMs, seconds)
        val earliest = frameTimesMs.minOrNull()
        return buckets.mapIndexed { index, count ->
            val bucketEndMs = nowMs - (seconds - 1 - index) * 1_000L
            when {
                count > 0 -> NowLinkTick.Received
                mode == NowLinkMode.Sync -> NowLinkTick.Idle
                earliest == null || bucketEndMs <= earliest -> NowLinkTick.Idle
                else -> NowLinkTick.Missed
            }
        }
    }

    private fun dataLink(
        displayState: NowDisplayState,
        state: BleUiState,
        nowMs: Long,
        frameAgeMs: Long?,
    ): NowDataLink {
        val mode = when {
            displayState.isOffline -> NowLinkMode.Offline
            displayState.isSyncing -> NowLinkMode.Sync
            displayState == NowDisplayState.Stale -> NowLinkMode.Stale
            else -> NowLinkMode.Live
        }
        val times = state.inboundFrameTimesMs
        val ticks = linkTicks(times, nowMs, mode)
        val received = ticks.count { it == NowLinkTick.Received }
        val missed = ticks.count { it == NowLinkTick.Missed }
        val framesInWindow = frameBuckets(times, nowMs).sum()
        val dataSets = (if (state.hasAFrame) 1 else 0) + (if (state.hasBFrame) 1 else 0)
        val connecting = state.collectionState?.phase == CollectionPhase.CONNECTING ||
            (state.collectionState == null && state.connectionState == ConnectionState.CONNECTING)
        val syncDetail = if (connecting && dataSets == 0) {
            "connecting to the meter"
        } else {
            "receiving ${dataSets.coerceAtMost(2)} of 2 data sets"
        }
        return when (mode) {
            NowLinkMode.Live -> NowDataLink(
                expanded = false,
                mode = mode,
                ticks = ticks,
                stat = "$received/$LINK_SECONDS $DOT " + if (missed <= STEADY_MAX_MISSED) "steady" else "gaps",
                caption = null,
                captionTone = NowTone.Neutral,
            )
            NowLinkMode.Stale -> NowDataLink(
                expanded = true,
                mode = mode,
                ticks = ticks,
                stat = "$received/$LINK_SECONDS frames",
                caption = "Frames stopped ${(frameAgeMs ?: 0L) / 1_000L}s ago $DOT values may be outdated",
                captionTone = NowTone.Discharge,
            )
            NowLinkMode.Sync -> NowDataLink(
                expanded = true,
                mode = mode,
                ticks = ticks,
                stat = "$framesInWindow frames",
                caption = if (displayState == NowDisplayState.FirstSync) {
                    "First connection $DOT $syncDetail"
                } else {
                    "Reconnected $DOT refreshing values ($syncDetail)"
                },
                captionTone = NowTone.Charge,
            )
            NowLinkMode.Offline -> NowDataLink(
                expanded = false,
                mode = mode,
                ticks = ticks,
                stat = "no link",
                caption = null,
                captionTone = NowTone.Neutral,
            )
        }
    }

    /** "+164.7" / "\u221286.3" / "0.0": explicit plus for positives, true minus for negatives. */
    fun formatSigned(value: Double, decimals: Int): String {
        val rounded = formatNumber(abs(value), decimals)
        val isZero = rounded.all { it == '0' || it == '.' }
        return when {
            isZero -> rounded
            value > 0.0 -> "+$rounded"
            else -> KmfNumberText.TRUE_MINUS + rounded
        }
    }

    fun formatNumber(value: Double, decimals: Int): String =
        KmfNumberText.withTrueMinus(String.format(Locale.US, "%.${decimals}f", value))

    private fun formatCapacity(capacityAh: Double, hasValues: Boolean): String = when {
        !hasValues || capacityAh <= 0.0 -> KmfNumberText.MISSING
        capacityAh == capacityAh.roundToInt().toDouble() -> capacityAh.roundToInt().toString()
        else -> formatNumber(capacityAh, 1)
    }
}
