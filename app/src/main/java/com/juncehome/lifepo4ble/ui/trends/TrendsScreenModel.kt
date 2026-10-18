package com.juncehome.lifepo4ble.ui.trends

import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportInfo
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExporter
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfCounterBucket
import com.juncehome.lifepo4ble.data.KmfHistorySummary
import com.juncehome.lifepo4ble.data.KmfSampleBucketRow
import com.juncehome.lifepo4ble.ui.components.KmfNumberText
import com.juncehome.lifepo4ble.ui.components.TrendChartColorMode
import com.juncehome.lifepo4ble.ui.components.TrendChartPoint
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Time range selector; [windowMs] is the chart window and [bucketMs] the chart aggregation bucket. */
enum class TrendsRange(val label: String, val windowMs: Long, val bucketMs: Long) {
    Live("Live", 15 * 60_000L, 5_000L),
    Day("24 h", 24 * 3_600_000L, 5 * 60_000L),
    Week("7 d", 7 * 86_400_000L, 45 * 60_000L),
    Month("30 d", 30 * 86_400_000L, 3 * 3_600_000L),
}

enum class TrendsMetric(val label: String, val unit: String, val decimals: Int, val signed: Boolean) {
    Power("Power", "W", 0, true),
    Soc("SoC", "%", 0, false),
    Voltage("Voltage", "V", 2, false),
    Current("Current", "A", 1, true),
}

/** Everything the ViewModel loads for one range; the reducer turns it into [TrendsScreenState]. */
data class TrendsData(
    val nowMs: Long,
    val buckets: List<KmfSampleBucketRow>,
    val latest: KmfBatterySampleEntity?,
    val counterBuckets: List<KmfCounterBucket>,
    val summary: KmfHistorySummary,
)

sealed interface TrendsExportStatus {
    data object Idle : TrendsExportStatus
    data class Running(val rowsWritten: Int) : TrendsExportStatus
    data class Done(val rowCount: Int) : TrendsExportStatus
    data class Failed(val message: String) : TrendsExportStatus
    data object Cleared : TrendsExportStatus
    data class ClearFailed(val message: String) : TrendsExportStatus
}

/** Energy slot layout for a range (hour or day slots) and how far back counters must be read. */
object TrendsEnergySlots {
    private const val HOUR_MS = 3_600_000L

    /** Start instants of the energy slots for [range]; the last slot ends at [nowMs]. */
    fun slotStarts(range: TrendsRange, nowMs: Long, zone: ZoneId): List<Long> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        return when (range) {
            TrendsRange.Live -> {
                val midnight = now.truncatedTo(ChronoUnit.DAYS)
                generateSequence(midnight) { it.plusHours(1) }
                    .takeWhile { it.toInstant().toEpochMilli() <= nowMs }
                    .map { it.toInstant().toEpochMilli() }
                    .toList()
            }
            TrendsRange.Day -> {
                val first = now.truncatedTo(ChronoUnit.HOURS).minusHours(23)
                (0 until 24).map { first.plusHours(it.toLong()).toInstant().toEpochMilli() }
            }
            TrendsRange.Week -> daySlots(now, 7)
            TrendsRange.Month -> daySlots(now, 30)
        }
    }

    private fun daySlots(now: ZonedDateTime, days: Long): List<Long> {
        val first = now.truncatedTo(ChronoUnit.DAYS).minusDays(days - 1)
        return (0 until days).map { first.plusDays(it).toInstant().toEpochMilli() }
    }

    /** First slot start minus one hour, so cross-boundary counter deltas exist for the first slot. */
    fun energyReadStartMs(range: TrendsRange, nowMs: Long, zone: ZoneId): Long {
        val first = slotStarts(range, nowMs, zone).firstOrNull() ?: nowMs
        return first - HOUR_MS
    }
}

data class EnergyBar(val upFraction: Float, val downFraction: Float)

data class TrendsEnergyState(
    val title: String,
    val inText: String,
    val outText: String,
    val netText: String,
    val bars: List<EnergyBar>,
    val fromLabel: String,
    val toLabel: String,
    val hasData: Boolean,
)

data class TrendsScreenState(
    val range: TrendsRange,
    val metric: TrendsMetric,
    val isLive: Boolean,
    val loading: Boolean,
    val points: List<TrendChartPoint>,
    val yMin: Double,
    val yMax: Double,
    val colorMode: TrendChartColorMode,
    val nowValue: String,
    val unit: String,
    val nowLabel: String,
    val tickLabels: List<String>,
    val minText: String,
    val avgText: String,
    val maxText: String,
    val scrubIndex: Int?,
    val tooltip: String?,
    val chartDescription: String,
    val energy: TrendsEnergyState,
    val countText: String,
    val summaryLine: String,
    val canClear: Boolean,
    val canExport: Boolean,
    val exportButtonLabel: String,
    val exportRunning: Boolean,
    val lastExportLine: String,
    val statusLine: String?,
    val adbCommand: String,
)

object TrendsScreenReducer {
    private const val MIN_GAP_MS = 11 * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    fun reduce(
        range: TrendsRange,
        metric: TrendsMetric,
        data: TrendsData?,
        lastExport: KmfBatteryHistoryExportInfo?,
        exportStatus: TrendsExportStatus,
        scrubIndex: Int?,
        zone: ZoneId,
        locale: Locale = Locale.US,
        nowMs: Long? = null,
    ): TrendsScreenState {
        val now = data?.nowMs ?: nowMs
        val buckets = data?.buckets.orEmpty().sortedBy { it.bucket }
        val points = if (now == null) emptyList() else chartPoints(range, metric, buckets, now)
        val colorMode = if (metric.signed) TrendChartColorMode.Signed else TrendChartColorMode.Single
        val (yMin, yMax) = yRange(metric, points)
        val selected = if (points.isEmpty() || scrubIndex == null) null else scrubIndex.coerceIn(0, points.lastIndex)
        val tooltip = selected?.let { index ->
            val bucket = buckets.getOrNull(index)
            val time = bucket?.let { timeFormat(range, zone, locale).format(Instant.ofEpochMilli((it.firstMs + it.lastMs) / 2)) }
            "${time ?: KmfNumberText.MISSING} \u00b7 ${format(points[index].value, metric, withUnit = true, locale = locale)}"
        }
        val latestValue = data?.latest?.let { metricOf(metric, it) }
        val nowValue = latestValue?.let { format(it, metric, withUnit = false, locale = locale) } ?: KmfNumberText.MISSING
        val stats = stats(metric, buckets, locale)
        val count = data?.summary?.count ?: 0L
        val running = exportStatus is TrendsExportStatus.Running
        return TrendsScreenState(
            range = range,
            metric = metric,
            isLive = range == TrendsRange.Live,
            loading = data == null,
            points = points,
            yMin = yMin,
            yMax = yMax,
            colorMode = colorMode,
            nowValue = nowValue,
            unit = metric.unit,
            nowLabel = if (range == TrendsRange.Live) "now" else "latest",
            tickLabels = if (now == null) emptyList() else tickLabels(range, now, zone, locale),
            minText = stats[0],
            avgText = stats[1],
            maxText = stats[2],
            scrubIndex = selected,
            tooltip = tooltip,
            chartDescription = description(range, metric, nowValue, stats, points.isEmpty()),
            energy = energy(range, data, now, zone, locale),
            countText = String.format(Locale.US, "%,d", count),
            summaryLine = summaryLine(count, data?.summary?.earliestMs, data?.summary?.dbSizeBytes ?: 0L, zone, locale),
            canClear = count > 0 && !running,
            canExport = count > 0 && !running,
            exportButtonLabel = (exportStatus as? TrendsExportStatus.Running)
                ?.let { "Exporting\u2026 ${it.rowsWritten} rows" } ?: "Export CSV",
            exportRunning = running,
            lastExportLine = lastExportLine(lastExport, now, zone, locale),
            statusLine = statusLine(exportStatus),
            adbCommand = KmfBatteryHistoryExporter.ADB_PULL_COMMAND,
        )
    }

    private fun metricOf(metric: TrendsMetric, sample: KmfBatterySampleEntity): Double = when (metric) {
        TrendsMetric.Power -> sample.powerW
        TrendsMetric.Soc -> sample.socPercent
        TrendsMetric.Voltage -> sample.voltageV
        TrendsMetric.Current -> sample.currentA
    }

    private fun avgOf(metric: TrendsMetric, row: KmfSampleBucketRow): Double = when (metric) {
        TrendsMetric.Power -> row.avgPowerW
        TrendsMetric.Soc -> row.avgSocPercent
        TrendsMetric.Voltage -> row.avgVoltageV
        TrendsMetric.Current -> row.avgCurrentA
    }

    private fun minOf(metric: TrendsMetric, row: KmfSampleBucketRow): Double = when (metric) {
        TrendsMetric.Power -> row.minPowerW
        TrendsMetric.Soc -> row.minSocPercent
        TrendsMetric.Voltage -> row.minVoltageV
        TrendsMetric.Current -> row.minCurrentA
    }

    private fun maxOf(metric: TrendsMetric, row: KmfSampleBucketRow): Double = when (metric) {
        TrendsMetric.Power -> row.maxPowerW
        TrendsMetric.Soc -> row.maxSocPercent
        TrendsMetric.Voltage -> row.maxVoltageV
        TrendsMetric.Current -> row.maxCurrentA
    }

    private fun chartPoints(
        range: TrendsRange,
        metric: TrendsMetric,
        buckets: List<KmfSampleBucketRow>,
        nowMs: Long,
    ): List<TrendChartPoint> {
        val start = nowMs - range.windowMs
        val gapMs = max(3 * range.bucketMs, MIN_GAP_MS)
        var previousMid: Long? = null
        return buckets.map { row ->
            val mid = (row.firstMs + row.lastMs) / 2
            val fraction = ((mid - start).toDouble() / range.windowMs).coerceIn(0.0, 1.0).toFloat()
            val gap = previousMid?.let { mid - it > gapMs } ?: false
            previousMid = mid
            TrendChartPoint(xFraction = fraction, value = avgOf(metric, row), startsSegment = gap)
        }
    }

    private fun yRange(metric: TrendsMetric, points: List<TrendChartPoint>): Pair<Double, Double> {
        if (metric.signed) {
            val epsilon = Math.pow(10.0, -metric.decimals.toDouble())
            val peak = max(points.maxOfOrNull { abs(it.value) } ?: 0.0, epsilon)
            return -peak to peak
        }
        if (points.isEmpty()) {
            return if (metric == TrendsMetric.Soc) 0.0 to 100.0 else 0.0 to 1.0
        }
        val low = points.minOf { it.value }
        val high = points.maxOf { it.value }
        if (high - low <= 0.0) {
            val pad = max(1.0, abs(low) * 0.01)
            return low - pad to high + pad
        }
        val pad = (high - low) * 0.15
        return low - pad to high + pad
    }

    private fun stats(metric: TrendsMetric, buckets: List<KmfSampleBucketRow>, locale: Locale): List<String> {
        val weight = buckets.sumOf { it.sampleCount.toLong() }
        if (buckets.isEmpty() || weight <= 0L) {
            return listOf(KmfNumberText.MISSING, KmfNumberText.MISSING, KmfNumberText.MISSING)
        }
        val low = buckets.minOf { minOf(metric, it) }
        val high = buckets.maxOf { maxOf(metric, it) }
        val average = buckets.sumOf { avgOf(metric, it) * it.sampleCount } / weight
        return listOf(low, average, high).map { format(it, metric, withUnit = true, locale = locale) }
    }

    /** Formats [value] with the metric's decimals, "+" for positive signed metrics and the true minus sign. */
    fun format(value: Double, metric: TrendsMetric, withUnit: Boolean, locale: Locale = Locale.US): String {
        var text = String.format(locale, "%.${metric.decimals}f", value)
        if (text.toDoubleOrNull() == 0.0) {
            text = text.removePrefix("-")
        }
        val shown = if (metric.signed) KmfNumberText.signed(text, value) else KmfNumberText.withTrueMinus(text)
        return if (withUnit) "$shown ${metric.unit}" else shown
    }

    private fun formatter(pattern: String, zone: ZoneId, locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, locale).withZone(zone)

    private fun timeFormat(range: TrendsRange, zone: ZoneId, locale: Locale): DateTimeFormatter = when (range) {
        TrendsRange.Live -> formatter("HH:mm", zone, locale)
        TrendsRange.Day, TrendsRange.Week -> formatter("EEE HH:mm", zone, locale)
        TrendsRange.Month -> formatter("d MMM", zone, locale)
    }

    private fun tickLabels(range: TrendsRange, nowMs: Long, zone: ZoneId, locale: Locale): List<String> {
        val start = nowMs - range.windowMs
        fun at(offsetMs: Long, pattern: String) = formatter(pattern, zone, locale).format(Instant.ofEpochMilli(start + offsetMs))
        return when (range) {
            TrendsRange.Live -> listOf("\u221215 min", "\u221210", "\u22125", "now")
            TrendsRange.Day -> (0..3).map { at(it * 6 * HOUR_MS, "HH:mm") } + "now"
            TrendsRange.Week -> listOf(0L, 2L, 4L, 6L).map { at(it * DAY_MS, "EEE") }
            TrendsRange.Month -> listOf(0L, 10L, 20L, 30L).map { at(it * DAY_MS, "d MMM") }
        }
    }

    private fun energy(
        range: TrendsRange,
        data: TrendsData?,
        nowMs: Long?,
        zone: ZoneId,
        locale: Locale,
    ): TrendsEnergyState {
        val title = when (range) {
            TrendsRange.Live -> "today by hour"
            TrendsRange.Day -> "last 24 h by hour"
            TrendsRange.Week -> "last 7 days"
            TrendsRange.Month -> "last 30 days"
        }
        if (nowMs == null) {
            return TrendsEnergyState(title, "--", "--", "--", emptyList(), "--", "now", false)
        }
        val starts = TrendsEnergySlots.slotStarts(range, nowMs, zone)
        val fine = TrendsEnergyMath.fineEnergy(data?.counterBuckets.orEmpty())
        val hasData = TrendsEnergyMath.hasData(fine, starts, nowMs)
        val slots = TrendsEnergyMath.slots(fine, starts, nowMs)
        val from = starts.firstOrNull()?.let {
            val pattern = when (range) {
                TrendsRange.Live, TrendsRange.Day -> "HH:mm"
                TrendsRange.Week -> "EEE"
                TrendsRange.Month -> "d MMM"
            }
            formatter(pattern, zone, locale).format(Instant.ofEpochMilli(it))
        } ?: "--"
        if (!hasData) {
            return TrendsEnergyState(
                title, "--", "--", "--", slots.map { EnergyBar(0f, 0f) }, from, "now", false,
            )
        }
        val totalIn = slots.sumOf { it.inKwh }
        val totalOut = slots.sumOf { it.outKwh }
        val peak = slots.maxOfOrNull { max(it.inKwh, it.outKwh) } ?: 0.0
        val bars = slots.map {
            if (peak <= 0.0) EnergyBar(0f, 0f) else EnergyBar((it.inKwh / peak).toFloat(), (it.outKwh / peak).toFloat())
        }
        val net = totalIn - totalOut
        val netAbs = String.format(locale, "%.2f", abs(net))
        val netText = if (net >= 0.0 || netAbs.toDouble() == 0.0) "+$netAbs" else KmfNumberText.TRUE_MINUS + netAbs
        return TrendsEnergyState(
            title = title,
            inText = String.format(locale, "%.2f", totalIn),
            outText = String.format(locale, "%.2f", totalOut),
            netText = netText,
            bars = bars,
            fromLabel = from,
            toLabel = "now",
            hasData = true,
        )
    }

    private fun summaryLine(count: Long, earliestMs: Long?, dbSizeBytes: Long, zone: ZoneId, locale: Locale): String {
        if (count <= 0L) return "No readings saved yet"
        val grouped = String.format(Locale.US, "%,d", count)
        val since = earliestMs?.let { " since " + formatter("d MMM", zone, locale).format(Instant.ofEpochMilli(it)) }.orEmpty()
        val size = if (dbSizeBytes > 0L) " \u00b7 ${formatDbSize(dbSizeBytes)}" else ""
        return "$grouped readings$since$size \u00b7 kept until you clear them"
    }

    /** Decimal units, matching Android Settings storage readouts. */
    internal fun formatDbSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%d KB", maxOf(1L, bytes / 1_000L))
    }

    private fun lastExportLine(info: KmfBatteryHistoryExportInfo?, nowMs: Long?, zone: ZoneId, locale: Locale): String {
        if (info == null) return "No export yet"
        val modified = Instant.ofEpochMilli(info.modifiedMs)
        val today = nowMs?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == modified.atZone(zone).toLocalDate()
        } ?: false
        val whenText = if (today) {
            "today " + formatter("HH:mm", zone, locale).format(modified)
        } else {
            formatter("d MMM HH:mm", zone, locale).format(modified)
        }
        return "Last export $whenText \u00b7 ${info.fileName}"
    }

    private fun statusLine(status: TrendsExportStatus): String? = when (status) {
        TrendsExportStatus.Idle, is TrendsExportStatus.Running -> null
        is TrendsExportStatus.Done -> "Exported ${status.rowCount} " + if (status.rowCount == 1) "reading" else "readings"
        is TrendsExportStatus.Failed -> "Export failed: ${status.message}"
        TrendsExportStatus.Cleared -> "Saved history cleared"
        is TrendsExportStatus.ClearFailed -> "Clear failed: ${status.message}"
    }

    private fun description(
        range: TrendsRange,
        metric: TrendsMetric,
        nowValue: String,
        stats: List<String>,
        empty: Boolean,
    ): String {
        val head = "${metric.label} over ${range.label}"
        return if (empty) {
            "$head, no data"
        } else {
            "$head, latest $nowValue ${metric.unit}, min ${stats[0]}, average ${stats[1]}, max ${stats[2]}"
        }
    }
}
