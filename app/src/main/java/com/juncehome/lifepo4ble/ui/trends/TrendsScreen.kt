package com.juncehome.lifepo4ble.ui.trends

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.data.KmfHistorySummary
import com.juncehome.lifepo4ble.data.KmfSampleBucketRow
import com.juncehome.lifepo4ble.ui.components.KmfLightButton
import com.juncehome.lifepo4ble.ui.components.SectionCard
import com.juncehome.lifepo4ble.ui.components.TrendChart
import com.juncehome.lifepo4ble.ui.components.ValueWithUnit
import com.juncehome.lifepo4ble.ui.components.rememberTrendChartAxisInset
import com.juncehome.lifepo4ble.ui.components.screenHeader
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val TAG = "KMF-BLE"

/** Everything the Trends tab can ask for. */
data class TrendsCallbacks(
    val onSelectRange: (TrendsRange) -> Unit = {},
    val onSelectMetric: (TrendsMetric) -> Unit = {},
    val onStepEarlier: () -> Unit = {},
    val onStepLater: () -> Unit = {},
    /** Returns why the range was rejected, or null when applied. */
    val onApplyCustom: (fromMs: Long, toMs: Long) -> TrendsCustomError? = { _, _ -> null },
    val onScrub: (Int) -> Unit = {},
    val onExport: () -> Unit = {},
    val onCopyAdb: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/** Stateful entry: collects the ViewModel, holds the scrub position and reduces to screen state. */
@Composable
fun TrendsRoute(
    viewModel: TrendsViewModel,
    contentPadding: PaddingValues,
    headerTrailing: @Composable () -> Unit = {},
) {
    val range by viewModel.range.collectAsStateWithLifecycle()
    val offset by viewModel.offset.collectAsStateWithLifecycle()
    val custom by viewModel.custom.collectAsStateWithLifecycle()
    val metric by viewModel.metric.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val lastExport by viewModel.lastExport.collectAsStateWithLifecycle()
    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(range, offset, custom, metric) { scrubIndex = null }
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val state by remember(range, offset, custom, metric, data, lastExport, exportStatus, scrubIndex) {
        derivedStateOf {
            TrendsScreenReducer.reduce(
                range = range,
                metric = metric,
                data = data,
                lastExport = lastExport,
                exportStatus = exportStatus,
                scrubIndex = scrubIndex,
                zone = zone,
                nowMs = System.currentTimeMillis(),
                offset = offset,
                custom = custom,
            )
        }
    }
    val callbacks = remember(viewModel, context) {
        TrendsCallbacks(
            onSelectRange = {
                AppLog.d("Trends: range tapped ${it.label}", TAG)
                viewModel.selectRange(it)
            },
            onSelectMetric = {
                AppLog.d("Trends: metric tapped ${it.label}", TAG)
                viewModel.selectMetric(it)
            },
            onStepEarlier = {
                AppLog.d("Trends: earlier period tapped", TAG)
                viewModel.stepEarlier()
            },
            onStepLater = {
                AppLog.d("Trends: later period tapped", TAG)
                viewModel.stepLater()
            },
            onApplyCustom = { from, to -> viewModel.applyCustom(from, to) },
            onScrub = { scrubIndex = it },
            onExport = {
                AppLog.d("Trends: export tapped", TAG)
                viewModel.exportCsv()
            },
            onCopyAdb = {},
            onClear = {
                AppLog.d("Trends: clear confirmed", TAG)
                viewModel.clearHistory()
            },
        )
    }
    TrendsScreen(
        state = state,
        contentPadding = contentPadding,
        callbacks = callbacks,
        zone = zone,
        headerTrailing = headerTrailing,
        onCopy = { text ->
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("KMF history export", text))
                AppLog.d("Trends: ADB command copied", TAG)
            } catch (error: RuntimeException) {
                AppLog.e("Trends: copying ADB command failed", error, TAG)
            }
        },
    )
}

@Composable
fun TrendsScreen(
    state: TrendsScreenState,
    contentPadding: PaddingValues,
    callbacks: TrendsCallbacks,
    zone: ZoneId = ZoneId.systemDefault(),
    headerTrailing: @Composable () -> Unit = {},
    onCopy: (String) -> Unit = {},
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KmfTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(KmfDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(KmfDimens.SectionGap),
    ) {
        TrendsHeader(headerTrailing)
        RangeChips(state.range, callbacks.onSelectRange)
        if (state.isCustom) {
            CustomRangePanel(state, zone, callbacks.onApplyCustom)
        } else {
            WindowStepper(state, callbacks.onStepEarlier, callbacks.onStepLater)
        }
        ChartCard(state, callbacks)
        EnergyCard(state.energy)
        HistoryCard(
            state = state,
            callbacks = callbacks,
            onCopy = onCopy,
            onClearTapped = {
                AppLog.d("Trends: clear tapped; asking for confirmation", TAG)
                confirmClear = true
            },
        )
    }
    if (confirmClear) {
        ClearHistoryDialog(
            onConfirm = {
                confirmClear = false
                callbacks.onClear()
            },
            onDismiss = {
                AppLog.d("Trends: clear canceled", TAG)
                confirmClear = false
            },
        )
    }
}

/** Title row with the shared header geometry (see [screenHeader]). */
@Composable
private fun TrendsHeader(trailing: @Composable () -> Unit) {
    val colors = KmfTheme.colors
    Row(
        modifier = Modifier.screenHeader(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Trends",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        trailing()
    }
}

@Composable
private fun ChartCard(state: TrendsScreenState, callbacks: TrendsCallbacks) {
    val colors = KmfTheme.colors
    SectionCard(
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
        verticalSpacing = 10.dp,
    ) {
        MetricSelector(state.metric, callbacks.onSelectMetric)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            ValueWithUnit(
                value = state.nowValue,
                unit = state.nowUnitText,
                valueStyle = KmfTheme.numbers.large.copy(fontSize = 28.sp, lineHeight = 34.sp),
                unitStyle = KmfTheme.numbers.largeUnit,
            )
            Text(
                state.metricCaption,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = colors.textSecondary,
            )
        }
        val axisInset = rememberTrendChartAxisInset(if (state.points.isEmpty()) null else state.yTicks)
        if (state.points.isEmpty()) {
            EmptyChart(loading = state.loading, description = state.chartDescription)
        } else {
            ScrubChart(state, axisInset, callbacks.onScrub)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = axisInset, end = if (state.points.isEmpty()) 0.dp else 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            state.tickLabels.forEach { tick ->
                Text(
                    tick,
                    style = KmfTheme.numbers.caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = colors.hairline)
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn("Min", state.minText, Alignment.Start, Modifier.weight(1f))
            StatColumn("Average", state.avgText, Alignment.CenterHorizontally, Modifier.weight(1f))
            StatColumn("Max", state.maxText, Alignment.End, Modifier.weight(1f))
        }
    }
}

/** Five equal unit-only segments (W, %, V, A, Ah) in a dark track. */
@Composable
private fun MetricSelector(selected: TrendsMetric, onSelect: (TrendsMetric) -> Unit) {
    val colors = KmfTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background, RoundedCornerShape(12.dp))
            .padding(4.dp)
            .semantics { contentDescription = "Metric" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TrendsMetric.entries.forEach { metric ->
            val isSelected = metric == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(if (isSelected) colors.charge.pillFill else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable(role = Role.RadioButton) { onSelect(metric) }
                    .semantics {
                        this.selected = isSelected
                        contentDescription = metric.fullName
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = metric.unit,
                    style = KmfTheme.numbers.small.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) colors.charge.accent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, align: Alignment.Horizontal, modifier: Modifier) {
    val colors = KmfTheme.colors
    Column(modifier = modifier.padding(top = 4.dp), horizontalAlignment = align) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.textSecondary)
        Text(
            value,
            style = KmfTheme.numbers.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun EmptyChart(loading: Boolean, description: String) {
    val colors = KmfTheme.colors
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .drawBehind {
                drawRoundRect(
                    color = colors.border,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (loading) "Loading\u2026" else "No readings in this range yet",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
        )
    }
}

private val CHART_HEIGHT = 190.dp

@Composable
private fun ScrubChart(state: TrendsScreenState, axisInset: Dp, onScrub: (Int) -> Unit) {
    val colors = KmfTheme.colors
    val insetPx = with(LocalDensity.current) { axisInset.toPx() }
    val endPadPx = with(LocalDensity.current) { 4.dp.toPx() }
    var widthPx by remember { mutableStateOf(0) }
    var tipWidthPx by remember { mutableStateOf(0) }
    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        TrendChart(
            points = state.points,
            yMin = state.yMin,
            yMax = state.yMax,
            colorMode = state.colorMode,
            contentDescription = state.chartDescription,
            height = CHART_HEIGHT,
            yTicks = state.yTicks,
            scrubIndex = state.scrubIndex,
            onScrub = onScrub,
        )
        val tooltip = state.tooltip
        val index = state.scrubIndex
        if (tooltip != null && index != null && widthPx > 0) {
            val x = insetPx + state.points[index].xFraction * (widthPx - insetPx - endPadPx).coerceAtLeast(1f)
            val left = (x - tipWidthPx / 2f).coerceIn(0f, (widthPx - tipWidthPx).coerceAtLeast(0).toFloat())
            Text(
                text = tooltip,
                style = KmfTheme.numbers.caption,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), 0) }
                    .onSizeChanged { tipWidthPx = it.width }
                    .background(colors.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun EnergyCard(energy: TrendsEnergyState) {
    val colors = KmfTheme.colors
    SectionCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Energy \u00b7 ${energy.title}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = buildAnnotatedString {
                    append("net ")
                    pushStyle(
                        SpanStyle(
                            fontFamily = KmfTheme.numbers.small.fontFamily,
                            color = colors.textPrimary,
                        ),
                    )
                    append(energy.netText)
                    pop()
                    append(" kWh")
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnergyTile("In \u00b7 charged", energy.inText, colors.charge.accent, colors.charge.cardFill, Modifier.weight(1f))
            EnergyTile("Out \u00b7 used", energy.outText, colors.discharge.accent, colors.discharge.cardFill, Modifier.weight(1f))
        }
        EnergyBars(energy)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(energy.fromLabel, style = KmfTheme.numbers.caption.copy(fontSize = 11.sp), color = colors.textTertiary)
            Text(energy.toLabel, style = KmfTheme.numbers.caption.copy(fontSize = 11.sp), color = colors.textTertiary)
        }
    }
}

@Composable
private fun EnergyTile(label: String, value: String, accent: Color, fill: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(fill, RoundedCornerShape(KmfDimens.TileRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = accent)
        ValueWithUnit(
            value = value,
            unit = "kWh",
            valueStyle = KmfTheme.numbers.medium,
            unitStyle = KmfTheme.numbers.mediumUnit,
        )
    }
}

@Composable
private fun EnergyBars(energy: TrendsEnergyState) {
    val colors = KmfTheme.colors
    val up = colors.charge.accent
    val down = colors.discharge.accent
    val divider = colors.border
    val description = if (energy.hasData) {
        "Energy in ${energy.inText} kWh above the line, energy out ${energy.outText} kWh below, " +
            "${energy.bars.size} periods from ${energy.fromLabel} to ${energy.toLabel}"
    } else {
        "Energy per period, no data"
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description },
    ) {
        val count = energy.bars.size
        if (count == 0) return@Canvas
        val gap = (if (count > 20) 2.dp else 6.dp).toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val mid = size.height / 2f
        val radius = CornerRadius(3.dp.toPx())
        energy.bars.forEachIndexed { i, bar ->
            val x = i * (barWidth + gap)
            val upH = (mid * bar.upFraction.coerceIn(0f, 1f))
            val downH = (mid * bar.downFraction.coerceIn(0f, 1f))
            if (upH > 0f) {
                drawRoundRect(up, Offset(x, mid - upH), Size(barWidth, upH), CornerRadius(radius.x, radius.y))
                drawRect(up, Offset(x, mid - upH.coerceAtMost(radius.y)), Size(barWidth, upH.coerceAtMost(radius.y)))
            }
            if (downH > 0f) {
                drawRoundRect(down, Offset(x, mid), Size(barWidth, downH), CornerRadius(radius.x, radius.y))
                drawRect(down, Offset(x, mid), Size(barWidth, downH.coerceAtMost(radius.y)))
            }
        }
        drawLine(divider, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun HistoryCard(
    state: TrendsScreenState,
    callbacks: TrendsCallbacks,
    onCopy: (String) -> Unit,
    onClearTapped: () -> Unit,
) {
    val colors = KmfTheme.colors
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000L)
            copied = false
        }
    }
    SectionCard(contentPadding = PaddingValues(0.dp), verticalSpacing = 0.dp) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Saved history", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(
                text = state.summaryLine,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textSecondary,
            )
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KmfLightButton(
                label = state.exportButtonLabel,
                onClick = callbacks.onExport,
                enabled = state.canExport,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = state.lastExportLine,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.textTertiary,
            )
            TextButton(
                onClick = {
                    onCopy(state.adbCommand)
                    callbacks.onCopyAdb()
                    copied = true
                },
                modifier = Modifier.heightIn(min = KmfDimens.MinTouchTarget),
            ) {
                Text(
                    text = if (copied) "Copied" else "Copy ADB command",
                    color = colors.charge.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            state.statusLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSoft,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        HorizontalDivider(color = colors.hairline)
        TextButton(
            onClick = onClearTapped,
            enabled = state.canClear,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                "Clear saved history\u2026",
                color = if (state.canClear) colors.error.onTint else colors.textTertiary,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun ClearHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = KmfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSoft,
        title = { Text("Clear saved history?") },
        text = {
            Text(
                "All saved readings are deleted permanently and cannot be recovered. " +
                    "Background collection keeps running and starts saving new readings.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", color = colors.error.onTint, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textPrimary) }
        },
    )
}

// ---- Previews ----

private const val PREVIEW_NOW_MS = 1_760_798_000_000L

private fun previewRows(range: TrendsRange): List<KmfSampleBucketRow> {
    val count = 48
    return (0 until count).map { i ->
        val t = PREVIEW_NOW_MS - range.windowMs + range.windowMs * i / count
        val p = 180.0 * kotlin.math.sin(i / 5.0) - 20.0
        KmfSampleBucketRow(
            bucket = t / range.bucketMs, sampleCount = 10, firstMs = t, lastMs = t + range.bucketMs / 2,
            avgPowerW = p, minPowerW = p - 20, maxPowerW = p + 20,
            avgSocPercent = 60.0 + i / 3.0, minSocPercent = 59.0 + i / 3.0, maxSocPercent = 61.0 + i / 3.0,
            avgVoltageV = 13.2 + p / 2000, minVoltageV = 13.1, maxVoltageV = 13.4,
            avgCurrentA = p / 13.2, minCurrentA = (p - 20) / 13.2, maxCurrentA = (p + 20) / 13.2,
        )
    }
}

private fun previewState(range: TrendsRange, empty: Boolean = false, scrub: Int? = null): TrendsScreenState {
    val data = TrendsData(
        nowMs = PREVIEW_NOW_MS,
        buckets = if (empty) emptyList() else previewRows(range),
        latest = null,
        counterBuckets = emptyList(),
        summary = KmfHistorySummary(count = if (empty) 0 else 128_430, earliestMs = PREVIEW_NOW_MS - 40 * 86_400_000L),
    )
    val base = TrendsScreenReducer.reduce(
        range, TrendsMetric.Power, data, null, TrendsExportStatus.Idle, scrub, ZoneId.of("UTC"),
    )
    return if (empty) {
        base
    } else {
        base.copy(
            nowValue = "+164.7",
            energy = TrendsEnergyState(
                title = base.energy.title, inText = "3.42", outText = "2.87", netText = "+0.55",
                bars = (0 until 24).map { EnergyBar(((it * 7) % 10) / 10f, ((it * 3) % 10) / 10f) },
                fromLabel = "00:00", toLabel = "now", hasData = true,
            ),
        )
    }
}

@Composable
private fun PreviewHost(state: TrendsScreenState) {
    KmfBleTheme { TrendsScreen(state = state, contentPadding = PaddingValues(0.dp), callbacks = TrendsCallbacks()) }
}

@Preview(name = "Trends Live", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1320)
@Composable
private fun TrendsLivePreview() = PreviewHost(previewState(TrendsRange.Live, scrub = 20))

@Preview(name = "Trends 24 h", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1320)
@Composable
private fun TrendsDayPreview() = PreviewHost(previewState(TrendsRange.Day))

@Preview(name = "Trends 7 d", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1320)
@Composable
private fun TrendsWeekPreview() = PreviewHost(previewState(TrendsRange.Week))

@Preview(name = "Trends 30 d", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1320)
@Composable
private fun TrendsMonthPreview() = PreviewHost(previewState(TrendsRange.Month))

@Preview(name = "Trends empty", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1320)
@Composable
private fun TrendsEmptyPreview() = PreviewHost(previewState(TrendsRange.Day, empty = true))

@Preview(
    name = "Trends font 1.6", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 2000,
    fontScale = 1.6f,
)
@Composable
private fun TrendsLargeFontPreview() = PreviewHost(previewState(TrendsRange.Week))
