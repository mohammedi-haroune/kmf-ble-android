package com.juncehome.lifepo4ble.ui.trends

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/**
 * Time range presets. [windowMs] is the chart window and [bucketMs] the chart aggregation bucket
 * (about 60-300 points per window); [energySlotMs] is the Energy card bar size (0 = chosen from the span).
 */
enum class TrendsRange(
    val label: String,
    val description: String,
    val windowMs: Long,
    val bucketMs: Long,
    val energySlotMs: Long,
    val energyTitle: String,
) {
    Min5("5 m", "Last 5 minutes", 5 * MINUTE_MS, SECOND_MS, MINUTE_MS, "last 5 min by min"),
    Min15("15 m", "Last 15 minutes", 15 * MINUTE_MS, 5 * SECOND_MS, MINUTE_MS, "last 15 min by min"),
    Hour1("1 h", "Last 1 hour", HOUR_MS, 30 * SECOND_MS, 5 * MINUTE_MS, "last 1 h by 5 min"),
    Hour2("2 h", "Last 2 hours", 2 * HOUR_MS, MINUTE_MS, 10 * MINUTE_MS, "last 2 h by 10 min"),
    Hour6("6 h", "Last 6 hours", 6 * HOUR_MS, 3 * MINUTE_MS, 30 * MINUTE_MS, "last 6 h by 30 min"),
    Hour12("12 h", "Last 12 hours", 12 * HOUR_MS, 5 * MINUTE_MS, HOUR_MS, "last 12 h by hour"),
    Day("24 h", "Last 24 hours", 24 * HOUR_MS, 10 * MINUTE_MS, HOUR_MS, "last 24 h by hour"),
    Day2("2 d", "Last 2 days", 2 * DAY_MS, 15 * MINUTE_MS, 4 * HOUR_MS, "last 2 days by 4 h"),
    Week("7 d", "Last 7 days", 7 * DAY_MS, HOUR_MS, DAY_MS, "last 7 days"),
    Month("30 d", "Last 30 days", 30 * DAY_MS, 4 * HOUR_MS, DAY_MS, "last 30 days"),
    Custom("Custom", "Custom range", 2 * DAY_MS, 15 * MINUTE_MS, 0L, "selected period"),
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

    /** The window for [range]; [offset] windows back from [nowMs] (ignored for Custom). */
    fun resolve(range: TrendsRange, offset: Int, custom: TrendsCustomRange?, nowMs: Long): TrendsWindow {
        if (range == TrendsRange.Custom) {
            val chosen = custom ?: TrendsCustomRanges.default(nowMs)
            val end = minOf(chosen.toMs, nowMs)
            val start = minOf(chosen.fromMs, end - MINUTE_MS)
            return TrendsWindow(range, start, end, bucketForSpan(end - start), 0)
        }
        val steps = offset.coerceAtLeast(0)
        val end = nowMs - steps * range.windowMs
        return TrendsWindow(range, end - range.windowMs, end, range.bucketMs, steps)
    }

    /** "Today 12:32 - 14:32", "Sat 14:32 - Mon 14:32", "12 Oct - 19 Oct"; the end reads "now" when the window ends now. */
    fun label(window: TrendsWindow, nowMs: Long, zone: ZoneId, locale: Locale = Locale.US): String {
        fun fmt(pattern: String, ms: Long) =
            DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(ms))
        fun end(pattern: String) = if (window.atNow) "now" else fmt(pattern, window.endMs)
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(window.startMs), zone)
        val endAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(window.endMs), zone)
        val span = window.windowMs
        return when {
            span < 7 * DAY_MS && start.toLocalDate() == endAt.toLocalDate() -> {
                val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate()
                val day = when (start.toLocalDate()) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> fmt("EEE d MMM", window.startMs)
                }
                "$day ${fmt("HH:mm", window.startMs)} - ${end("HH:mm")}"
            }
            span < 7 * DAY_MS -> "${fmt("EEE HH:mm", window.startMs)} - ${end("EEE HH:mm")}"
            else -> "${fmt("d MMM", window.startMs)} - ${end("d MMM")}"
        }
    }

    /** Hover / scrub time format for a window span. */
    fun timePattern(window: TrendsWindow): String = when {
        window.windowMs <= 15 * MINUTE_MS -> "HH:mm:ss"
        window.windowMs <= 12 * HOUR_MS -> "HH:mm"
        window.windowMs <= 7 * DAY_MS -> "EEE HH:mm"
        else -> "d MMM"
    }

    /**
     * X axis labels at round wall-clock times (whole minutes, quarter hours, midnights...) placed at
     * their real position in the window, never seconds. Picks the smallest step that gives at most
     * [MAX_AXIS_TICKS] labels, plus "now" at the right edge when the window ends now. "now" owns the
     * last [END_ZONE] of the axis, so 5 m and 15 m always show four times plus "now". The screen also
     * drops any label that would touch its neighbour, so long formats never overlap.
     */
    fun axisTicks(window: TrendsWindow, zone: ZoneId, locale: Locale = Locale.US): List<TrendsAxisTick> {
        val span = window.windowMs.coerceAtLeast(1L)
        val step = axisSteps.firstOrNull { span / it <= MAX_AXIS_TICKS && tickTimes(window, it, zone).size <= MAX_AXIS_TICKS }
            ?: axisSteps.last()
        val ticks = tickTimes(window, step, zone).map { ms ->
            TrendsAxisTick(((ms - window.startMs).toDouble() / span).toFloat(), tickLabel(ms, step, window, zone, locale))
        }
        if (!window.atNow) return ticks
        return ticks.filter { it.fraction <= 1f - END_ZONE + 1e-4f } + TrendsAxisTick(1f, "now", isEnd = true)
    }

    private const val MAX_AXIS_TICKS = 5
    private const val END_ZONE = 0.2f
    private val axisSteps = longArrayOf(
        MINUTE_MS, 2 * MINUTE_MS, 3 * MINUTE_MS, 5 * MINUTE_MS, 10 * MINUTE_MS, 15 * MINUTE_MS, 30 * MINUTE_MS,
        HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS, 6 * HOUR_MS, 12 * HOUR_MS, DAY_MS, 2 * DAY_MS, 7 * DAY_MS,
    )

    /** Round times inside (start, end): sub-day steps align to local midnight, 7 d steps to Mondays. */
    private fun tickTimes(window: TrendsWindow, step: Long, zone: ZoneId): List<Long> {
        val startDay = Instant.ofEpochMilli(window.startMs).atZone(zone).truncatedTo(ChronoUnit.DAYS)
        val first: ZonedDateTime
        val next: (ZonedDateTime) -> ZonedDateTime
        if (step >= DAY_MS) {
            val days = step / DAY_MS
            first = when (days) {
                7L -> startDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                else -> startDay.minusDays(Math.floorMod(startDay.toLocalDate().toEpochDay(), days))
            }
            next = { it.plusDays(days) }
        } else {
            val stepMinutes = step / MINUTE_MS
            val local = Instant.ofEpochMilli(window.startMs).atZone(zone)
            val minuteOfDay = local.hour * 60L + local.minute
            first = startDay.plusMinutes(minuteOfDay / stepMinutes * stepMinutes)
            next = { it.toLocalDateTime().plusMinutes(stepMinutes).atZone(zone) }
        }
        return generateSequence(first, next)
            .map { it.toInstant().toEpochMilli() }
            .dropWhile { it <= window.startMs }
            .takeWhile { it < window.endMs }
            .toList()
    }

    private fun tickLabel(ms: Long, step: Long, window: TrendsWindow, zone: ZoneId, locale: Locale): String {
        val time = Instant.ofEpochMilli(ms).atZone(zone)
        val pattern = when {
            step >= DAY_MS -> if (window.windowMs <= 14 * DAY_MS) "EEE" else "d MMM"
            window.windowMs > DAY_MS && time.hour == 0 && time.minute == 0 -> "EEE"
            else -> "HH:mm"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(time)
    }
}

/** One x axis label at [fraction] of the window (0 = start, 1 = end); [isEnd] is the "now" label. */
data class TrendsAxisTick(val fraction: Float, val label: String, val isEnd: Boolean = false)

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
