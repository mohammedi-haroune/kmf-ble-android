package com.juncehome.lifepo4ble.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.patrykandpatrick.vico.compose.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong

@Composable
fun LiveScreen(
    state: BleUiState,
    samples: List<KmfBatterySampleEntity>,
    contentPadding: PaddingValues,
) {
    var selectedRange by rememberSaveable { mutableStateOf(LiveRange.TenMinutes) }
    val content = buildLiveContent(state, samples, selectedRange)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Live",
            style = MaterialTheme.typography.headlineMedium,
        )

        // LiveSummaryCard(content = content)
        // LiveRangeSelector(
        //     selectedRange = selectedRange,
        //     onRangeSelected = { selectedRange = it },
        // )

        content.emptyState?.let { emptyState ->
            LiveEmptyStateCard(
                emptyState = emptyState,
                connectionLabel = content.connectionLabel,
            )
        } ?: content.charts.forEach { chart ->
            // LiveChartCard(
            //     chart = chart,
            //     range = selectedRange,
            // )
            if (chart.title == "Power") {
                LivelinePowerExperimentCard(
                    chart = chart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

enum class LiveRange(
    val label: String,
    val durationMs: Long,
) {
    TenMinutes("10 minutes", Duration.ofMinutes(10).toMillis()),
    ThirtyMinutes("30 minutes", Duration.ofMinutes(30).toMillis()),
    OneHour("1 hour", Duration.ofHours(1).toMillis()),
}

enum class LiveEmptyState {
    NoDevice,
    NoSamples,
}

data class LiveContent(
    val deviceName: String?,
    val connectionLabel: String,
    val lastUpdatedTimestampMs: Long?,
    val emptyState: LiveEmptyState?,
    val charts: List<LiveChartModel>,
)

data class LiveChartModel(
    val title: String,
    val unit: String,
    val latestValue: String,
    val points: List<LiveChartPoint>,
    val latestXSeconds: Double,
    val visibleXMinSeconds: Double,
    val visibleXMaxSeconds: Double,
    val renderedXMinSeconds: Double,
    val renderedXMaxSeconds: Double,
)

data class LiveChartPoint(
    val timestampMs: Long,
    val xSeconds: Double,
    val xMinutes: Double,
    val y: Double,
)

data class RenderedLiveChartPoint(
    val timestampMs: Long,
    val xSeconds: Double,
    val y: Double,
)

fun buildLiveContent(
    state: BleUiState,
    samples: List<KmfBatterySampleEntity>,
    range: LiveRange,
): LiveContent {
    val selectedDevice = state.selectedDevice
    if (selectedDevice == null) {
        return LiveContent(
            deviceName = null,
            connectionLabel = state.connectionState.toDisplayLabel(),
            lastUpdatedTimestampMs = null,
            emptyState = LiveEmptyState.NoDevice,
            charts = emptyList(),
        )
    }

    val deviceSamples = samples
        .asSequence()
        .filter { sample -> sample.deviceAddress == selectedDevice.address }
        .sortedBy { sample -> sample.timestampMs }
        .toList()

    if (deviceSamples.isEmpty()) {
        return LiveContent(
            deviceName = selectedDevice.name.orDashboardDeviceName(),
            connectionLabel = state.connectionState.toDisplayLabel(),
            lastUpdatedTimestampMs = null,
            emptyState = LiveEmptyState.NoSamples,
            charts = emptyList(),
        )
    }

    val newestTimestampMs = deviceSamples.last().timestampMs
    val earliestIncludedTimestampMs = newestTimestampMs - range.durationMs
    val rangeSamples = deviceSamples.filter { sample -> sample.timestampMs >= earliestIncludedTimestampMs }

    if (rangeSamples.isEmpty()) {
        return LiveContent(
            deviceName = selectedDevice.name.orDashboardDeviceName(),
            connectionLabel = state.connectionState.toDisplayLabel(),
            lastUpdatedTimestampMs = newestTimestampMs,
            emptyState = LiveEmptyState.NoSamples,
            charts = emptyList(),
        )
    }

    val groupedSamples = rangeSamples.groupToLatestSamplePerSecond()
    val firstTimestampMs = groupedSamples.first().bucketSecond * 1_000L
    return LiveContent(
        deviceName = selectedDevice.name.orDashboardDeviceName(),
        connectionLabel = state.connectionState.toDisplayLabel(),
        lastUpdatedTimestampMs = newestTimestampMs,
        emptyState = null,
        charts = listOf(
            // groupedSamples.toChart(
            //     title = "State of charge",
            //     unit = "%",
            //     firstTimestampMs = firstTimestampMs,
            //     selector = { sample -> sample.entity.socPercent },
            //     formatter = Double::toPercentText,
            // ),
            groupedSamples.toChart(
                title = "Power",
                unit = "W",
                firstTimestampMs = firstTimestampMs,
                selector = { sample -> sample.entity.powerW },
                formatter = { value -> value.toLivePowerText() },
            ),
            // groupedSamples.toChart(
            //     title = "Current",
            //     unit = "A",
            //     firstTimestampMs = firstTimestampMs,
            //     selector = { sample -> sample.entity.currentA },
            //     formatter = { value -> value.toUnitText("A") },
            // ),
            // groupedSamples.toChart(
            //     title = "Voltage",
            //     unit = "V",
            //     firstTimestampMs = firstTimestampMs,
            //     selector = { sample -> sample.entity.voltageV },
            //     formatter = { value -> value.toUnitText("V") },
            // ),
        ),
    )
}

@Composable
private fun LiveSummaryCard(content: LiveContent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = content.deviceName ?: "No meter selected",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Connection: ${content.connectionLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = content.lastUpdatedTimestampMs?.toLiveTimestampText() ?: "Last update: waiting for samples",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveRangeSelector(
    selectedRange: LiveRange,
    onRangeSelected: (LiveRange) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveRange.entries.forEach { range ->
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelected(range) },
                label = {
                    Text(range.label)
                },
            )
        }
    }
}

@Composable
private fun LiveEmptyStateCard(
    emptyState: LiveEmptyState,
    connectionLabel: String,
) {
    val title: String
    val body: String
    when (emptyState) {
        LiveEmptyState.NoDevice -> {
            title = "No meter selected"
            body = "Open Diagnostic to scan and connect before live charts can render."
        }
        LiveEmptyState.NoSamples -> {
            title = "No persisted samples yet"
            body = "The app has not stored battery samples for the current meter in the selected range. Current connection: $connectionLabel."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun LiveChartCard(
    chart: LiveChartModel,
    range: LiveRange,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = chart.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = chart.latestValue,
                style = MaterialTheme.typography.headlineSmall,
            )
            LiveLineChart(
                chart = chart,
                range = range,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        }
    }
}

@Composable
private fun LivelinePowerExperimentCard(
    chart: LiveChartModel,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            LivelinePowerChartWebView(
                chart = chart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            )
        }
    }
}

@Composable
private fun LiveLineChart(
    chart: LiveChartModel,
    range: LiveRange,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val scrollState = rememberVicoScrollState(
        initialScroll = Scroll.Absolute.End,
        autoScroll = Scroll.Absolute.End,
        autoScrollCondition = AutoScrollCondition.OnModelGrowth,
    )
    val startAxisValueFormatter = remember(chart.unit) {
        CartesianValueFormatter { _, value, _ ->
            // Power uses the same tiered rounding as the web liveline spike and the chart headline.
            if (chart.unit == "%") {
                String.format(Locale.US, "%.0f%%", value)
            } else if (chart.unit == "W") {
                value.toLivePowerText()
            } else {
                String.format(Locale.US, "%.1f %s", value, chart.unit)
            }
        }
    }
    val axisStartTimestampMs = chart.axisStartTimestampMs()
    val bottomAxisValueFormatter = remember(axisStartTimestampMs) {
        CartesianValueFormatter { _, value, _ ->
            if (axisStartTimestampMs == null) {
                ""
            } else {
                formatLiveXAxisLabel(value = value, firstTimestampMs = axisStartTimestampMs)
            }
        }
    }
    val renderedPoints = chart.renderedPoints()
    val xRangeProvider = remember { LiveXAxisRangeProvider() }
    xRangeProvider.minX = chart.renderedXMinSeconds
    xRangeProvider.maxX = chart.renderedXMaxSeconds

    LaunchedEffect(renderedPoints) {
        modelProducer.runTransaction {
            lineModel {
                series(
                    renderedPoints.map { point -> point.xSeconds },
                    renderedPoints.map { point -> point.y },
                )
            }
        }
    }

    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberLineCartesianLayer(rangeProvider = xRangeProvider),
                startAxis = VerticalAxis.rememberStart(valueFormatter = startAxisValueFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisValueFormatter),
            ),
            modelProducer,
            modifier = modifier,
            scrollState = scrollState,
        )
    }
}

private data class LiveSecondSample(
    val bucketSecond: Long,
    val entity: KmfBatterySampleEntity,
)

private fun List<KmfBatterySampleEntity>.groupToLatestSamplePerSecond(): List<LiveSecondSample> =
    asSequence()
        .groupBy { sample -> sample.timestampMs / 1_000L }
        .map { (bucketSecond, bucketSamples) ->
            LiveSecondSample(
                bucketSecond = bucketSecond,
                entity = bucketSamples.maxBy { sample -> sample.timestampMs },
            )
        }
        .sortedBy { sample -> sample.bucketSecond }

private fun List<LiveSecondSample>.toChart(
    title: String,
    unit: String,
    firstTimestampMs: Long,
    selector: (LiveSecondSample) -> Double,
    formatter: (Double) -> String,
): LiveChartModel {
    val points = map { sample ->
        val bucketTimestampMs = sample.bucketSecond * 1_000L
        val elapsedMs = bucketTimestampMs - firstTimestampMs
        val y = selector(sample)
        LiveChartPoint(
            timestampMs = sample.entity.timestampMs,
            xSeconds = (elapsedMs / 1_000.0).roundToChartPrecision(),
            xMinutes = (elapsedMs / 60_000.0).roundToChartPrecision(),
            y = if (unit == "W") y.roundToLivePowerChartValue() else y,
        )
    }
    val latestXSeconds = points.last().xSeconds
    val visibleXMinSeconds = (
        latestXSeconds - (LIVE_VISIBLE_WINDOW_SECONDS - LIVE_RIGHT_PADDING_SECONDS)
    ).coerceAtLeast(0.0)
    return LiveChartModel(
        title = title,
        unit = unit,
        latestValue = formatter(selector(last())),
        points = points,
        latestXSeconds = latestXSeconds,
        visibleXMinSeconds = visibleXMinSeconds,
        visibleXMaxSeconds = latestXSeconds,
        renderedXMinSeconds = 0.0,
        renderedXMaxSeconds = LIVE_VISIBLE_WINDOW_SECONDS,
    )
}

fun LiveChartModel.visiblePoints(): List<LiveChartPoint> =
    points.filter { point ->
        point.xSeconds >= visibleXMinSeconds && point.xSeconds <= visibleXMaxSeconds
    }

fun LiveChartModel.renderedPoints(): List<RenderedLiveChartPoint> =
    visiblePoints().map { point ->
        RenderedLiveChartPoint(
            timestampMs = point.timestampMs,
            xSeconds = (point.xSeconds - visibleXMinSeconds).roundToChartPrecision(),
            y = point.y,
        )
    }.withFutureHoldPoint(renderedXMaxSeconds)

private fun List<RenderedLiveChartPoint>.withFutureHoldPoint(
    renderedXMaxSeconds: Double,
): List<RenderedLiveChartPoint> {
    val latest = lastOrNull() ?: return this
    if (latest.xSeconds >= renderedXMaxSeconds) {
        return this
    }
    val holdPoint = latest.copy(
        timestampMs = latest.timestampMs + ((renderedXMaxSeconds - latest.xSeconds) * 1_000.0).roundToLong(),
        xSeconds = renderedXMaxSeconds,
    )
    return this + holdPoint
}

private fun LiveChartModel.axisStartTimestampMs(): Long? {
    val firstTimestampMs = points.firstOrNull()?.timestampMs ?: return null
    return firstTimestampMs + (visibleXMinSeconds * 1_000.0).roundToLong()
}

private class LiveXAxisRangeProvider : CartesianLayerRangeProvider {
    var minX: Double = 0.0
    var maxX: Double = LIVE_VISIBLE_WINDOW_SECONDS

    override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double = this.minX

    override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore): Double = this.maxX
}

fun formatLiveXAxisLabel(value: Double, firstTimestampMs: Long): String =
    LIVE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(firstTimestampMs + (value * 1_000.0).roundToLong())
            .atZone(ZoneId.systemDefault())
    )

fun Double.toLivePowerText(): String {
    if (!isFinite()) {
        return "-- W"
    }

    // Keep small power readings stable while still showing coarse values at higher load.
    val decimals = when {
        abs(this) <= 5.0 -> 2
        abs(this) <= 20.0 -> 1
        else -> 0
    }
    return String.format(Locale.US, "%.${decimals}f W", this)
}

private fun Double.roundToLivePowerChartValue(): Double {
    if (!isFinite()) {
        return this
    }

    val decimals = when {
        abs(this) <= 5.0 -> 2
        abs(this) <= 20.0 -> 1
        else -> 0
    }
    val scale = 10.0.pow(decimals)
    return round(this * scale) / scale
}

private fun Long.toLiveTimestampText(): String =
    "Last update: " + LIVE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    )

private fun Double.roundToChartPrecision(): Double =
    (this * 10_000.0).roundToLong() / 10_000.0

private const val LIVE_VISIBLE_WINDOW_SECONDS = 60.0
private const val LIVE_RIGHT_PADDING_SECONDS = 2.0

private val LIVE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")
