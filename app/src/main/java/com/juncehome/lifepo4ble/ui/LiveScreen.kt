package com.juncehome.lifepo4ble.ui

import androidx.compose.foundation.horizontalScroll
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
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Live",
            style = MaterialTheme.typography.headlineMedium,
        )

        LiveSummaryCard(content = content)
        LiveRangeSelector(
            selectedRange = selectedRange,
            onRangeSelected = { selectedRange = it },
        )

        content.emptyState?.let { emptyState ->
            LiveEmptyStateCard(
                emptyState = emptyState,
                connectionLabel = content.connectionLabel,
            )
        } ?: content.charts.forEach { chart ->
            LiveChartCard(
                chart = chart,
                range = selectedRange,
            )
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
)

data class LiveChartPoint(
    val xMinutes: Double,
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

    val firstTimestampMs = rangeSamples.first().timestampMs
    return LiveContent(
        deviceName = selectedDevice.name.orDashboardDeviceName(),
        connectionLabel = state.connectionState.toDisplayLabel(),
        lastUpdatedTimestampMs = newestTimestampMs,
        emptyState = null,
        charts = listOf(
            rangeSamples.toChart(
                title = "State of charge",
                unit = "%",
                firstTimestampMs = firstTimestampMs,
                selector = KmfBatterySampleEntity::socPercent,
                formatter = Double::toPercentText,
            ),
            rangeSamples.toChart(
                title = "Power",
                unit = "W",
                firstTimestampMs = firstTimestampMs,
                selector = KmfBatterySampleEntity::powerW,
                formatter = { value -> value.toUnitText("W") },
            ),
            rangeSamples.toChart(
                title = "Current",
                unit = "A",
                firstTimestampMs = firstTimestampMs,
                selector = KmfBatterySampleEntity::currentA,
                formatter = { value -> value.toUnitText("A") },
            ),
            rangeSamples.toChart(
                title = "Voltage",
                unit = "V",
                firstTimestampMs = firstTimestampMs,
                selector = KmfBatterySampleEntity::voltageV,
                formatter = { value -> value.toUnitText("V") },
            ),
        ),
    )
}

@Composable
private fun LiveSummaryCard(content: LiveContent) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
            body = "Open Diagnostics to scan and connect before live charts can render."
        }
        LiveEmptyState.NoSamples -> {
            title = "No persisted samples yet"
            body = "The app has not stored battery samples for the current meter in the selected range. Current connection: $connectionLabel."
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
private fun LiveLineChart(
    chart: LiveChartModel,
    range: LiveRange,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val startAxisValueFormatter = remember(chart.unit) {
        CartesianValueFormatter { _, value, _ ->
            if (chart.unit == "%") {
                String.format(Locale.US, "%.0f%%", value)
            } else {
                String.format(Locale.US, "%.1f %s", value, chart.unit)
            }
        }
    }
    val bottomAxisValueFormatter = remember(range) {
        CartesianValueFormatter { _, value, _ ->
            value.toMinuteLabel(range)
        }
    }

    LaunchedEffect(chart.points) {
        modelProducer.runTransaction {
            lineModel {
                series(
                    chart.points.map { point -> point.xMinutes },
                    chart.points.map { point -> point.y },
                )
            }
        }
    }

    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(valueFormatter = startAxisValueFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisValueFormatter),
            ),
            modelProducer,
            modifier = modifier,
        )
    }
}

private fun List<KmfBatterySampleEntity>.toChart(
    title: String,
    unit: String,
    firstTimestampMs: Long,
    selector: (KmfBatterySampleEntity) -> Double,
    formatter: (Double) -> String,
): LiveChartModel {
    val points = map { sample ->
        LiveChartPoint(
            xMinutes = ((sample.timestampMs - firstTimestampMs) / 60_000.0).roundToChartPrecision(),
            y = selector(sample),
        )
    }
    return LiveChartModel(
        title = title,
        unit = unit,
        latestValue = formatter(selector(last())),
        points = points,
    )
}

private fun Double.toMinuteLabel(range: LiveRange): String {
    val durationMs = (this * 60_000.0).roundToLong()
    val minutes = durationMs / 60_000L
    return when {
        range == LiveRange.OneHour -> String.format(Locale.US, "%dm", minutes)
        durationMs < 60_000L -> "${durationMs / 1_000L}s"
        else -> String.format(Locale.US, "%dm", minutes)
    }
}

private fun Long.toLiveTimestampText(): String =
    "Last update: " + LIVE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    )

private fun Double.roundToChartPrecision(): Double =
    (this * 10_000.0).roundToLong() / 10_000.0

private val LIVE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")
