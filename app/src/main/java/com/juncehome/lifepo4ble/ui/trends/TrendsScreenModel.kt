package com.juncehome.lifepo4ble.ui.trends

import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportInfo
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExporter
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfCounterBucket
import com.juncehome.lifepo4ble.data.KmfHistorySummary
import com.juncehome.lifepo4ble.data.KmfSampleBucketRow
import com.juncehome.lifepo4ble.ui.components.KmfNumberText
import com.juncehome.lifepo4ble.ui.components.TrendChartColorMode
import com.juncehome.lifepo4ble.ui.components.TrendChartMath
import com.juncehome.lifepo4ble.ui.components.TrendChartPoint
import com.juncehome.lifepo4ble.ui.components.TrendChartTick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** [fullName] is the accessible name of the unit-only segment and the caption next to the value. */
enum class TrendsMetric(
    val label: String,
    val unit: String,
    val decimals: Int,
    val signed: Boolean,
    val fullName: String,
) {
    Power("Power", "W", 0, true, "Power"),
    Soc("SoC", "%", 0, false, "State of charge"),
    Voltage("Voltage", "V", 2, false, "Voltage"),
    Current("Current", "A", 1, true, "Current"),
    Capacity("Capacity", "Ah", 1, false, "Capacity"),
}

/** Everything the ViewModel loads for one range; the reducer turns it into [TrendsScreenState]. */
data class TrendsData(
    val nowMs: Long,
    val buckets: List<KmfSampleBucketRow>,
    val latest: KmfBatterySampleEntity?,
    val counterBuckets: List<KmfCounterBucket>,
    val summary: KmfHistorySummary,
    /** The window these rows were loaded for; null falls back to the preset window ending at [nowMs]. */
    val window: TrendsWindow? = null,
)

sealed interface TrendsExportStatus {
    data object Idle : TrendsExportStatus
    data class Running(val rowsWritten: Int) : TrendsExportStatus
    data class Done(val rowCount: Int) : TrendsExportStatus
    data class Failed(val message: String) : TrendsExportStatus
    data object Cleared : TrendsExportStatus
    data class ClearFailed(val message: String) : TrendsExportStatus
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
    /** The window ends now, so new samples keep arriving. */
    val isLive: Boolean,
    /** Pulse the line's end point: the window ends now and is short enough (1 h or less) for growth to show. */
    val liveEdge: Boolean,
    val loading: Boolean,
    val points: List<TrendChartPoint>,
    val yMin: Double,
    val yMax: Double,
    val colorMode: TrendChartColorMode,
    val nowValue: String,
    val unit: String,
    val nowLabel: String,
    /** Unit text after the value, e.g. "W" or "/ 200 Ah" for Capacity. */
    val nowUnitText: String,
    /** Right-aligned caption of the value line, e.g. "Power - now". */
    val metricCaption: String,
    val yTicks: List<TrendChartTick>,
    val axisTicks: List<TrendsAxisTick>,
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
    val offset: Int,
    val windowLabel: String,
    val canEarlier: Boolean,
    val canLater: Boolean,
    val isCustom: Boolean,
    val customFromMs: Long,
    val customToMs: Long,
    val nowMs: Long,
    val earliestMs: Long?,
)

object TrendsScreenReducer {
    /** Time (bucket midpoint) of chart point [index]; points map 1:1 to the time-sorted buckets. */
    fun scrubTimeAt(data: TrendsData?, index: Int): Long? =
        data?.buckets.orEmpty().sortedBy { it.bucket }.getOrNull(index)?.let { (it.firstMs + it.lastMs) / 2 }

    /**
     * Point index nearest to [timeMs], so a scrub stays on the same moment while the window slides.
     * Null once that moment has left the window.
     */
    fun scrubIndexAt(data: TrendsData?, timeMs: Long?): Int? {
        if (data == null || timeMs == null) return null
        val window = data.window
        if (window != null && (timeMs < window.startMs || timeMs > window.endMs)) return null
        val mids = data.buckets.sortedBy { it.bucket }.map { (it.firstMs + it.lastMs) / 2 }
        if (mids.isEmpty()) return null
        return mids.indices.minByOrNull { abs(mids[it] - timeMs) }
    }

    private const val MIN_GAP_MS = 11 * 60_000L
    private const val LIVE_EDGE_MAX_WINDOW_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

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
        offset: Int = 0,
        custom: TrendsCustomRange? = null,
    ): TrendsScreenState {
        val now = data?.nowMs ?: nowMs
        val window = if (now == null) null else data?.window ?: TrendsWindows.resolve(range, offset, custom, now)
        val buckets = data?.buckets.orEmpty().sortedBy { it.bucket }
        val points = if (window == null) emptyList() else chartPoints(window, metric, buckets)
        val colorMode = if (metric.signed) TrendChartColorMode.Signed else TrendChartColorMode.Single
        val (yMin, yMax) = yRange(metric, points)
        val selected = if (points.isEmpty() || scrubIndex == null) null else scrubIndex.coerceIn(0, points.lastIndex)
        val tooltip = selected?.let { index ->
            val bucket = buckets.getOrNull(index)
            val time = bucket?.let {
                formatter(TrendsWindows.timePattern(window!!), zone, locale).format(Instant.ofEpochMilli((it.firstMs + it.lastMs) / 2))
            }
            "${time ?: KmfNumberText.MISSING} \u00b7 ${format(points[index].value, metric, withUnit = true, locale = locale)}"
        }
        val latestValue = data?.latest?.let { metricOf(metric, it) }
        val nowValue = latestValue?.let { format(it, metric, withUnit = false, locale = locale) } ?: KmfNumberText.MISSING
        val stats = stats(metric, buckets, locale)
        val count = data?.summary?.count ?: 0L
        val earliest = data?.summary?.earliestMs
        val running = exportStatus is TrendsExportStatus.Running
        // Every window that ends now reloads on each new sample, whatever its length.
        val live = window?.atNow == true
        val steppable = range != TrendsRange.Custom
        val nowLabel = if (live) "now" else "latest"
        val capacitySuffix = if (metric == TrendsMetric.Capacity) capacitySuffix(data?.latest, locale) else null
        val customRange = if (range == TrendsRange.Custom && window != null) {
            TrendsCustomRange(window.startMs, window.endMs)
        } else {
            custom ?: TrendsCustomRanges.default(now ?: 0L)
        }
        return TrendsScreenState(
            range = range,
            metric = metric,
            isLive = live,
            liveEdge = live && range.windowMs <= LIVE_EDGE_MAX_WINDOW_MS,
            loading = data == null,
            points = points,
            yMin = yMin,
            yMax = yMax,
            colorMode = colorMode,
            nowValue = nowValue,
            unit = metric.unit,
            nowLabel = nowLabel,
            nowUnitText = if (capacitySuffix != null) "$capacitySuffix ${metric.unit}" else metric.unit,
            metricCaption = "${metric.fullName} - $nowLabel",
            yTicks = if (points.isEmpty()) emptyList() else TrendChartMath.niceYTicks(yMin, yMax),
            axisTicks = if (window == null) emptyList() else TrendsWindows.axisTicks(window, zone, locale),
            minText = stats[0],
            avgText = stats[1],
            maxText = stats[2],
            scrubIndex = selected,
            tooltip = tooltip,
            chartDescription = description(range, metric, nowValue, stats, points.isEmpty()),
            energy = energy(window, data, zone, locale),
            countText = String.format(Locale.US, "%,d", count),
            summaryLine = summaryLine(count, earliest, data?.summary?.dbSizeBytes ?: 0L, zone, locale),
            canClear = count > 0 && !running,
            canExport = count > 0 && !running,
            exportButtonLabel = (exportStatus as? TrendsExportStatus.Running)
                ?.let { "Exporting\u2026 ${it.rowsWritten} rows" } ?: "Export CSV",
            exportRunning = running,
            lastExportLine = lastExportLine(lastExport, now, zone, locale),
            statusLine = statusLine(exportStatus),
            adbCommand = KmfBatteryHistoryExporter.ADB_PULL_COMMAND,
            offset = window?.offset ?: 0,
            windowLabel = if (window == null || now == null) KmfNumberText.MISSING else TrendsWindows.label(window, now, zone, locale),
            canEarlier = steppable && window != null && earliest != null && window.startMs > earliest,
            canLater = steppable && (window?.offset ?: 0) > 0,
            isCustom = range == TrendsRange.Custom,
            customFromMs = customRange.fromMs,
            customToMs = customRange.toMs,
            nowMs = now ?: 0L,
            earliestMs = earliest,
        )
    }

    /** "/ 200" for the Capacity value line; null when the meter capacity is unknown. */
    private fun capacitySuffix(latest: KmfBatterySampleEntity?, locale: Locale): String? {
        val capacity = latest?.capacityAh ?: return null
        if (!(capacity > 0.0)) return null
        val whole = String.format(locale, "%.1f", capacity).endsWith(".0")
        return "/ " + String.format(locale, if (whole) "%.0f" else "%.1f", capacity)
    }

    private fun metricOf(metric: TrendsMetric, sample: KmfBatterySampleEntity): Double = when (metric) {
        TrendsMetric.Power -> sample.powerW
        TrendsMetric.Soc -> sample.socPercent
        TrendsMetric.Voltage -> sample.voltageV
        TrendsMetric.Current -> sample.currentA
        TrendsMetric.Capacity -> sample.remainingAh
    }

    private fun avgOf(metric: TrendsMetric, row: KmfSampleBucketRow): Double = when (metric) {
        TrendsMetric.Power -> row.avgPowerW
        TrendsMetric.Soc -> row.avgSocPercent
        TrendsMetric.Voltage -> row.avgVoltageV
        TrendsMetric.Current -> row.avgCurrentA
        TrendsMetric.Capacity -> row.avgRemainingAh
    }

    private fun minOf(metric: TrendsMetric, row: KmfSampleBucketRow): Double = when (metric) {
        TrendsMetric.Power -> row.minPowerW
        TrendsMetric.Soc -> row.minSocPercent
        TrendsMetric.Voltage -> row.minVoltageV
        TrendsMetric.Current -> row.minCurrentA
        TrendsMetric.Capacity -> row.minRemainingAh
    }

    private fun maxOf(metric: TrendsMetric, row: KmfSampleBucketRow): Double = when (metric) {
        TrendsMetric.Power -> row.maxPowerW
        TrendsMetric.Soc -> row.maxSocPercent
        TrendsMetric.Voltage -> row.maxVoltageV
        TrendsMetric.Current -> row.maxCurrentA
        TrendsMetric.Capacity -> row.maxRemainingAh
    }

    private fun chartPoints(
        window: TrendsWindow,
        metric: TrendsMetric,
        buckets: List<KmfSampleBucketRow>,
    ): List<TrendChartPoint> {
        val span = window.windowMs.coerceAtLeast(1L)
        val gapMs = max(3 * window.bucketMs, MIN_GAP_MS)
        var previousMid: Long? = null
        return buckets.map { row ->
            val mid = (row.firstMs + row.lastMs) / 2
            val fraction = ((mid - window.startMs).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
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

    private fun energy(
        window: TrendsWindow?,
        data: TrendsData?,
        zone: ZoneId,
        locale: Locale,
    ): TrendsEnergyState {
        if (window == null) {
            return TrendsEnergyState("", "--", "--", "--", emptyList(), "--", "now", false)
        }
        val layout = TrendsEnergySlots.layout(window, zone)
        val starts = layout.starts
        val title = energyTitle(window, layout)
        val pattern = when {
            layout.slotDays == 1 && starts.size <= 7 -> "EEE"
            layout.slotDays > 0 -> "d MMM"
            window.windowMs <= DAY_MS -> "HH:mm"
            else -> "EEE HH:mm"
        }
        val to = if (window.atNow) "now" else formatter(pattern, zone, locale).format(Instant.ofEpochMilli(window.endMs))
        val fine = TrendsEnergyMath.fineEnergy(data?.counterBuckets.orEmpty(), layout.fineMs)
        val hasData = TrendsEnergyMath.hasData(fine, starts, window.endMs)
        val slots = TrendsEnergyMath.slots(fine, starts, window.endMs)
        val from = starts.firstOrNull()?.let { formatter(pattern, zone, locale).format(Instant.ofEpochMilli(it)) } ?: "--"
        if (!hasData) {
            return TrendsEnergyState(title, "--", "--", "--", slots.map { EnergyBar(0f, 0f) }, from, to, false)
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
            toLabel = to,
            hasData = true,
        )
    }

    private fun energyTitle(window: TrendsWindow, layout: TrendsEnergyLayout): String = when {
        window.range == TrendsRange.Custom ->
            "selected period by ${TrendsEnergySlots.slotLabel(layout.slotMs)}"
        window.offset > 0 -> window.range.energyTitle.removePrefix("last ")
        else -> window.range.energyTitle
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
