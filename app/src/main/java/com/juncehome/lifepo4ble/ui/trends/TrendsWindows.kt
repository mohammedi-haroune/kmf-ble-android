package com.juncehome.lifepo4ble.ui.trends

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/**
 * Time range presets. [windowMs] is the chart window and [bucketMs] the chart aggregation bucket
 * (about 60-200 points per window); [energySlotMs] is the Energy card bar size (0 = chosen from the span).
 */
enum class TrendsRange(
    val label: String,
    val description: String,
    val windowMs: Long,
    val bucketMs: Long,
    val energySlotMs: Long,
    val energyTitle: String,
    /** Adds a small extra gap before this chip to group minutes / hours / days. */
    val startsGroup: Boolean = false,
) {
    Live("Live", "Last 15 minutes", 15 * MINUTE_MS, 5 * SECOND_MS, HOUR_MS, "today by hour"),
    Hour1("1 h", "Last 1 hour", HOUR_MS, 30 * SECOND_MS, 5 * MINUTE_MS, "last 1 h by 5 min", startsGroup = true),
    Hour2("2 h", "Last 2 hours", 2 * HOUR_MS, MINUTE_MS, 10 * MINUTE_MS, "last 2 h by 10 min"),
    Hour6("6 h", "Last 6 hours", 6 * HOUR_MS, 3 * MINUTE_MS, 30 * MINUTE_MS, "last 6 h by 30 min"),
    Hour12("12 h", "Last 12 hours", 12 * HOUR_MS, 5 * MINUTE_MS, HOUR_MS, "last 12 h by hour"),
    Day("24 h", "Last 24 hours", 24 * HOUR_MS, 10 * MINUTE_MS, HOUR_MS, "last 24 h by hour"),
    Day2("2 d", "Last 2 days", 2 * DAY_MS, 15 * MINUTE_MS, 4 * HOUR_MS, "last 2 days by 4 h", startsGroup = true),
    Week("7 d", "Last 7 days", 7 * DAY_MS, HOUR_MS, DAY_MS, "last 7 days"),
    Month("30 d", "Last 30 days", 30 * DAY_MS, 4 * HOUR_MS, DAY_MS, "last 30 days"),
    Custom("Custom", "Custom range", 2 * DAY_MS, 15 * MINUTE_MS, 0L, "selected period", startsGroup = true),
}

/** User-chosen custom span (epoch ms); validate with [TrendsCustomRanges.validate]. */
data class TrendsCustomRange(val fromMs: Long, val toMs: Long)

/** One concrete chart window: [startMs, endMs] aggregated into [bucketMs] buckets. */
data class TrendsWindow(
    val range: TrendsRange,
    val startMs: Long,
    val endMs: Long,
    val bucketMs: Long,
    /** Number of whole windows the window is shifted back from now (always 0 for Live and Custom). */
    val offset: Int,
) {
    val windowMs: Long get() = endMs - startMs

    /** True when the window ends at the current time. */
    val atNow: Boolean get() = offset == 0 && range != TrendsRange.Custom
}

/** Why a custom range cannot be applied. */
enum class TrendsCustomError(val message: String) {
    FromNotBeforeTo("Start must be before end"),
    ToInFuture("End can't be in the future"),
}

object TrendsCustomRanges {
    const val DEFAULT_SPAN_MS: Long = 2 * DAY_MS

    fun default(nowMs: Long): TrendsCustomRange = TrendsCustomRange(nowMs - DEFAULT_SPAN_MS, nowMs)

    /** From must be before To and To must not be in the future. */
    fun validate(fromMs: Long, toMs: Long, nowMs: Long): TrendsCustomError? = when {
        fromMs >= toMs -> TrendsCustomError.FromNotBeforeTo
        toMs > nowMs -> TrendsCustomError.ToInFuture
        else -> null
    }

    /** Date from a Material3 date picker (UTC midnight millis) plus a local time, in [zone]. */
    fun combine(pickerUtcDateMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(pickerUtcDateMs).atZone(ZoneId.of("UTC")).toLocalDate()
        return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    /** "12 Oct 08:00". */
    fun format(ms: Long, zone: ZoneId, locale: Locale = Locale.US): String =
        DateTimeFormatter.ofPattern("d MMM HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(ms))

    /** "2 d", "5 h 30 min", "45 min". */
    fun spanText(spanMs: Long): String {
        val minutes = (spanMs.coerceAtLeast(0L) + MINUTE_MS / 2) / MINUTE_MS
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return when {
            days > 0 && hours == 0L && mins == 0L -> "$days d"
            days > 0 && mins == 0L -> "$days d $hours h"
            days > 0 -> "$days d $hours h $mins min"
            hours > 0 && mins == 0L -> "$hours h"
            hours > 0 -> "$hours h $mins min"
            else -> "$mins min"
        }
    }

    /** "History from 3 Oct - 2 d selected". */
    fun hint(earliestMs: Long?, fromMs: Long, toMs: Long, zone: ZoneId, locale: Locale = Locale.US): String {
        val since = earliestMs?.let {
            "from " + DateTimeFormatter.ofPattern("d MMM", locale).withZone(zone).format(Instant.ofEpochMilli(it))
        } ?: "is empty"
        return "History $since - ${spanText(toMs - fromMs)} selected"
    }
}

object TrendsWindows {
    private const val TARGET_POINTS = 120L
    private val bucketSteps = longArrayOf(
        5 * SECOND_MS, 10 * SECOND_MS, 15 * SECOND_MS, 30 * SECOND_MS, MINUTE_MS, 2 * MINUTE_MS, 3 * MINUTE_MS,
        5 * MINUTE_MS, 10 * MINUTE_MS, 15 * MINUTE_MS, 30 * MINUTE_MS, HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS,
        4 * HOUR_MS, 6 * HOUR_MS, 12 * HOUR_MS, DAY_MS,
    )

    /** Chart bucket for a custom span: the smallest step giving at most about [TARGET_POINTS] points. */
    fun bucketForSpan(spanMs: Long): Long {
        val raw = spanMs / TARGET_POINTS
        return bucketSteps.firstOrNull { it >= raw } ?: bucketSteps.last()
    }

    /** The window for [range]; [offset] windows back from [nowMs] (ignored for Live and Custom). */
    fun resolve(range: TrendsRange, offset: Int, custom: TrendsCustomRange?, nowMs: Long): TrendsWindow {
        if (range == TrendsRange.Custom) {
            val chosen = custom ?: TrendsCustomRanges.default(nowMs)
            val end = minOf(chosen.toMs, nowMs)
            val start = minOf(chosen.fromMs, end - MINUTE_MS)
            return TrendsWindow(range, start, end, bucketForSpan(end - start), 0)
        }
        val steps = if (range == TrendsRange.Live) 0 else offset.coerceAtLeast(0)
        val end = nowMs - steps * range.windowMs
        return TrendsWindow(range, end - range.windowMs, end, range.bucketMs, steps)
    }

    /** "Today 12:32 - 14:32", "Sat 14:32 - Mon 14:32", "12 Oct - 19 Oct", "Last 15 min" for Live. */
    fun label(window: TrendsWindow, nowMs: Long, zone: ZoneId, locale: Locale = Locale.US): String {
        if (window.range == TrendsRange.Live) return "Last 15 min"
        fun fmt(pattern: String, ms: Long) =
            DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(ms))
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(window.startMs), zone)
        val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(window.endMs), zone)
        val span = window.windowMs
        return when {
            span < 7 * DAY_MS && start.toLocalDate() == end.toLocalDate() -> {
                val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate()
                val day = when (start.toLocalDate()) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> fmt("EEE d MMM", window.startMs)
                }
                "$day ${fmt("HH:mm", window.startMs)} - ${fmt("HH:mm", window.endMs)}"
            }
            span < 7 * DAY_MS -> "${fmt("EEE HH:mm", window.startMs)} - ${fmt("EEE HH:mm", window.endMs)}"
            else -> "${fmt("d MMM", window.startMs)} - ${fmt("d MMM", window.endMs)}"
        }
    }

    /** Hover / scrub time format for a window span. */
    fun timePattern(window: TrendsWindow): String = when {
        window.windowMs <= 12 * HOUR_MS -> "HH:mm"
        window.windowMs <= 7 * DAY_MS -> "EEE HH:mm"
        else -> "d MMM"
    }

    /** X axis labels; the last one is "now" when the window ends now. */
    fun tickLabels(window: TrendsWindow, zone: ZoneId, locale: Locale = Locale.US): List<String> {
        fun at(ms: Long, pattern: String) =
            DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(ms))
        val start = window.startMs
        return when (window.range) {
            TrendsRange.Live -> listOf("−15 min", "−10", "−5", "now")
            TrendsRange.Week -> listOf(0L, 2L, 4L, 6L).map { at(start + it * DAY_MS, "EEE") }
            TrendsRange.Month -> listOf(0L, 10L, 20L, 30L).map { at(start + it * DAY_MS, "d MMM") }
            else -> {
                val pattern = when {
                    window.windowMs <= DAY_MS -> "HH:mm"
                    window.windowMs <= 3 * DAY_MS -> "EEE HH:mm"
                    else -> "d MMM"
                }
                (0..3).map { at(start + window.windowMs * it / 4, pattern) } +
                    if (window.atNow) "now" else at(window.endMs, pattern)
            }
        }
    }
}

/** Energy bar layout of one window: slot start instants plus the fine counter bucket to read. */
data class TrendsEnergyLayout(
    val starts: List<Long>,
    val slotMs: Long,
    val fineMs: Long,
    /** Whole days per slot, or 0 for sub-day slots. */
    val slotDays: Int,
)

/** Energy slot layout for a window (hour/minute/day slots) and how far back counters must be read. */
object TrendsEnergySlots {
    private const val MAX_SLOTS = 120
    private const val MAX_CUSTOM_SLOTS = 48
    private val customUnits = longArrayOf(
        15 * MINUTE_MS, 30 * MINUTE_MS, HOUR_MS, 2 * HOUR_MS, 4 * HOUR_MS, 6 * HOUR_MS, 12 * HOUR_MS, DAY_MS, 7 * DAY_MS,
    )

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun customUnit(spanMs: Long): Long =
        customUnits.firstOrNull { ceil(spanMs.toDouble() / it) <= MAX_CUSTOM_SLOTS } ?: customUnits.last()

    fun layout(window: TrendsWindow, zone: ZoneId): TrendsEnergyLayout {
        if (window.range == TrendsRange.Live) {
            val midnight = Instant.ofEpochMilli(window.endMs).atZone(zone).truncatedTo(ChronoUnit.DAYS)
            val starts = generateSequence(midnight) { it.plusHours(1) }
                .takeWhile { it.toInstant().toEpochMilli() <= window.endMs }
                .map { it.toInstant().toEpochMilli() }
                .toList()
            return TrendsEnergyLayout(starts, HOUR_MS, TrendsEnergyMath.FINE_BUCKET_MS, 0)
        }
        val span = window.windowMs.coerceAtLeast(1L)
        val unit = if (window.range == TrendsRange.Custom) customUnit(span) else window.range.energySlotMs
        val count = ceil(span.toDouble() / unit).toInt().coerceIn(1, MAX_SLOTS)
        val anchor = window.endMs - 1
        val fine = gcd(unit, TrendsEnergyMath.FINE_BUCKET_MS)
        if (unit % DAY_MS == 0L) {
            val days = unit / DAY_MS
            val lastDay = Instant.ofEpochMilli(anchor).atZone(zone).truncatedTo(ChronoUnit.DAYS)
            val starts = (0 until count).map { j ->
                lastDay.minusDays(days * (count - 1 - j)).toInstant().toEpochMilli()
            }
            return TrendsEnergyLayout(starts, unit, fine, days.toInt())
        }
        val offsetMs = zone.rules.getOffset(Instant.ofEpochMilli(anchor)).totalSeconds * SECOND_MS
        val lastStart = Math.floorDiv(anchor + offsetMs, unit) * unit - offsetMs
        val starts = (0 until count).map { j -> lastStart - (count - 1 - j) * unit }
        return TrendsEnergyLayout(starts, unit, fine, 0)
    }

    /** Start instants for [range] ending now (offset 0). */
    fun slotStarts(range: TrendsRange, nowMs: Long, zone: ZoneId): List<Long> =
        layout(TrendsWindows.resolve(range, 0, null, nowMs), zone).starts

    /** First slot start minus one hour, so cross-boundary counter deltas exist for the first slot. */
    fun energyReadStartMs(layout: TrendsEnergyLayout, fallbackMs: Long): Long =
        (layout.starts.firstOrNull() ?: fallbackMs) - HOUR_MS

    fun energyReadStartMs(range: TrendsRange, nowMs: Long, zone: ZoneId): Long =
        energyReadStartMs(layout(TrendsWindows.resolve(range, 0, null, nowMs), zone), nowMs)

    /** "5 min", "hour", "4 h", "day", "7 days". */
    fun slotLabel(slotMs: Long): String = when {
        slotMs < HOUR_MS -> "${slotMs / MINUTE_MS} min"
        slotMs == HOUR_MS -> "hour"
        slotMs < DAY_MS -> "${slotMs / HOUR_MS} h"
        slotMs == DAY_MS -> "day"
        else -> "${slotMs / DAY_MS} days"
    }
}
