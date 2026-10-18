package com.juncehome.lifepo4ble.ui.now

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.components.Banner
import com.juncehome.lifepo4ble.ui.components.BannerTone
import com.juncehome.lifepo4ble.ui.components.KmfIcons
import com.juncehome.lifepo4ble.ui.components.StatusPill
import com.juncehome.lifepo4ble.ui.components.TrendChart
import com.juncehome.lifepo4ble.ui.components.TrendChartColorMode
import com.juncehome.lifepo4ble.ui.components.TrendChartPoint
import com.juncehome.lifepo4ble.ui.components.ValueWithUnit
import com.juncehome.lifepo4ble.ui.theme.KmfAccent
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import java.time.ZoneId
import kotlinx.coroutines.delay

private const val TAG = "KMF-BLE"

/** Data link tick colors from the Now board that are not general theme tokens. */
private val LinkReceivedDim = Color(0xFF2E5E4A)
private val LinkOfflineReceived = Color(0xFF2A3038)

/**
 * Stateful entry for the Now tab: ticks the clock once per second (frame age, reconnect
 * countdown) and maps app state through [NowScreenReducer].
 */
@Composable
fun NowRoute(
    state: BleUiState,
    samples: List<KmfBatterySampleEntity>,
    contentPadding: PaddingValues,
    onOpenDevice: () -> Unit,
    onRetryCollection: () -> Unit,
    onStartCollection: () -> Unit,
    /** NoMeter banner "Set up": opens the first-run flow. */
    onSetUp: () -> Unit = onOpenDevice,
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val model = remember(state, samples, nowMs) {
        NowScreenReducer.reduce(state, samples, nowMs, ZoneId.systemDefault())
    }
    LaunchedEffect(model.displayState) {
        AppLog.d(
            "Now: display state=${model.displayState} pill='${model.pill.label}' pillAction=${model.pill.action} " +
                "link='${model.dataLink.stat}' banner='${model.banner?.title}' " +
                "phase=${state.collectionState?.phase} hasValues=${model.hasValues}",
            TAG,
        )
    }
    NowScreen(
        model = model,
        contentPadding = contentPadding,
        onOpenDevice = {
            AppLog.d("Now: meter name tapped, opening Device tab", TAG)
            onOpenDevice()
        },
        onBannerAction = { action ->
            AppLog.d("Now: banner action=$action state=${model.displayState}", TAG)
            when (action) {
                NowBannerAction.RetryNow, NowBannerAction.Retry -> onRetryCollection()
                NowBannerAction.Start -> onStartCollection()
                NowBannerAction.SetUp -> onSetUp()
            }
        },
    )
}

@Composable
fun NowScreen(
    model: NowScreenState,
    contentPadding: PaddingValues,
    onOpenDevice: () -> Unit,
    onBannerAction: (NowBannerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KmfTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(start = KmfDimens.ScreenPadding, end = KmfDimens.ScreenPadding, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(KmfDimens.SectionGap),
    ) {
        NowHeader(model.meterName, model.pill, onOpenDevice, onPillAction = onBannerAction)
        model.banner?.let { banner ->
            Banner(
                title = banner.title,
                body = banner.body,
                tone = banner.tone,
                icon = if (banner.tone == BannerTone.Error) KmfIcons.Warning else KmfIcons.BluetoothOff,
                actionLabel = banner.action?.label,
                onAction = banner.action?.let { action -> { onBannerAction(action) } },
            )
        }
        SocSection(model)
        FlowCard(model.flow)
        PowerChartCard(model.chart)
        DataLinkCard(model.dataLink)
        TotalsFooter(model.totalsIn, model.totalsOut)
    }
}

@Composable
private fun NowTone.accent(): KmfAccent {
    val colors = KmfTheme.colors
    return when (this) {
        NowTone.Charge -> colors.charge
        NowTone.Discharge -> colors.discharge
        NowTone.Live -> colors.live
        NowTone.Error -> colors.error
        NowTone.Neutral -> colors.neutral
        NowTone.Muted -> KmfAccent(colors.textTertiary)
    }
}

@Composable
private fun NowHeader(
    meterName: String,
    pill: NowPill,
    onOpenDevice: () -> Unit,
    onPillAction: (NowBannerAction) -> Unit,
) {
    val colors = KmfTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Outer row takes the free width so the pill stays pinned right; only the name and chevron are tappable.
        Row(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .heightIn(min = KmfDimens.MinTouchTarget)
                    .clickable(role = Role.Button, onClickLabel = "Open device settings", onClick = onOpenDevice)
                    .semantics { contentDescription = "$meterName, open device settings" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = meterName,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Icon(
                    imageVector = KmfIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        val action = pill.action
        StatusPill(
            label = pill.label,
            accent = pill.tone.accent(),
            indicator = pill.indicator,
            contentDescription = pill.contentDescription,
            icon = when (pill.icon) {
                NowPillIcon.Retry -> KmfIcons.Retry
                NowPillIcon.Play -> KmfIcons.Play
                NowPillIcon.None -> null
            },
            onClick = action?.let { a ->
                {
                    AppLog.d("Now: header pill tapped action=$a label='${pill.label}'", TAG)
                    onPillAction(a)
                }
            },
            onClickLabel = action?.label,
        )
    }
}

@Composable
private fun SocSection(model: NowScreenState) {
    val colors = KmfTheme.colors
    val numbers = KmfTheme.numbers
    val density = LocalDensity.current
    val fontScale = density.fontScale
    // The 100 sp hero is display type: cap its growth so large font sizes do not clip it.
    val heroScale = if (fontScale > 1f) 1f / fontScale else 1f
    val heroStyle = numbers.hero.copy(
        fontSize = numbers.hero.fontSize * heroScale,
        lineHeight = numbers.hero.lineHeight * heroScale,
    )
    val heroUnitStyle = numbers.heroUnit.copy(fontSize = numbers.heroUnit.fontSize * heroScale)
    val socColor = if (model.lowBattery) colors.error.onTint else colors.textPrimary

    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "State of charge ${model.soc} percent, ${model.voltage} volts, " +
                "${model.ahRemaining} of ${model.ahCapacity} amp hours"
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val hero: @Composable (Modifier) -> Unit = { mod ->
            ValueWithUnit(
                value = model.soc,
                unit = "%",
                unitSeparator = "",
                valueStyle = heroStyle,
                unitStyle = heroUnitStyle,
                valueColor = socColor,
                modifier = mod,
            )
        }
        val details: @Composable (Modifier, Alignment.Horizontal) -> Unit = { mod, align ->
            Column(modifier = mod, horizontalAlignment = align) {
                ValueWithUnit(value = model.voltage, unit = "V")
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.textPrimary)) { append(model.ahRemaining) }
                        append(" / ${model.ahCapacity} Ah")
                    },
                    style = numbers.small,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (fontScale > 1.3f) {
            // Large text: stack so neither value is clipped.
            hero(Modifier)
            details(Modifier, Alignment.Start)
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                hero(Modifier.alignBy(LastBaseline))
                Spacer(Modifier.weight(1f))
                details(Modifier.alignBy(LastBaseline), Alignment.End)
            }
        }
        BatteryBar(fraction = model.socFraction, accent = model.barTone.accent(), low = model.lowBattery)
        if (model.lowBattery) {
            LowBatteryRow(model.lowBatteryDetail.orEmpty())
        }
    }
}

@Composable
private fun BatteryBar(fraction: Float, accent: KmfAccent, low: Boolean) {
    val colors = KmfTheme.colors
    val borderColor = if (low) colors.error.accent.copy(alpha = 0.5f) else colors.border
    val emptyColor = colors.surface2
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .border(BorderStroke(2.dp, borderColor), RoundedCornerShape(9.dp))
                .padding(5.dp),
        ) {
            val gap = 3.dp.toPx()
            val segmentWidth = (size.width - gap * 9) / 10f
            val radius = CornerRadius(4.dp.toPx())
            for (index in 0 until 10) {
                val left = index * (segmentWidth + gap)
                val fill = (fraction * 10f - index).coerceIn(0f, 1f)
                drawRoundRect(emptyColor, Offset(left, 0f), Size(segmentWidth, size.height), radius)
                if (fill > 0f) {
                    clipRect(left = left, top = 0f, right = left + segmentWidth * fill, bottom = size.height) {
                        drawRoundRect(accent.accent, Offset(left, 0f), Size(segmentWidth, size.height), radius)
                    }
                }
            }
        }
        Box(
            Modifier
                .width(5.dp)
                .height(12.dp)
                .background(borderColor, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)),
        )
    }
}

@Composable
private fun LowBatteryRow(detail: String) {
    val colors = KmfTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.error.accent.copy(alpha = 0.12f), RoundedCornerShape(KmfDimens.TileRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KmfIcons.Warning, contentDescription = null, tint = colors.error.onTint, modifier = Modifier.size(18.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Low battery.") }
                append(" ")
                append(detail)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.error.onTint,
        )
    }
}

@Composable
private fun FlowCard(flow: NowFlow) {
    val colors = KmfTheme.colors
    val accent = flow.tone.accent()
    val muted = flow.tone == NowTone.Muted
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KmfDimens.CardRadius),
        color = if (muted) colors.surface else accent.cardFill,
        border = BorderStroke(KmfDimens.Hairline, if (muted) colors.hairline else accent.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BatteryCellIcon(
                color = accent.accent,
                tileColor = if (muted) colors.surface2 else accent.tileFill,
                animation = flow.animation,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(flow.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = accent.accent, maxLines = 1)
                Text(flow.eta, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                ValueWithUnit(value = flow.power, unit = "W")
                ValueWithUnit(
                    value = flow.current,
                    unit = "A",
                    valueStyle = KmfTheme.numbers.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    unitStyle = KmfTheme.numbers.small,
                )
            }
        }
    }
}

/** Battery outline with 4 cells; a wave runs bottom->top when charging, top->bottom when discharging. */
@Composable
private fun BatteryCellIcon(color: Color, tileColor: Color, animation: NowCellAnimation) {
    val animate = animation != NowCellAnimation.None && !KmfTheme.reduceMotion
    val progress = if (animate) {
        val transition = rememberInfiniteTransition(label = "cellWave")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
            label = "cellWaveProgress",
        )
        value
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tileColor, RoundedCornerShape(KmfDimens.TileRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 31.dp)) {
            val capW = 8.dp.toPx()
            val capH = 3.dp.toPx()
            drawRoundRect(color, Offset((size.width - capW) / 2f, 0f), Size(capW, capH), CornerRadius(1.dp.toPx()))
            val bodyTop = capH + 2.dp.toPx()
            val stroke = 2.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2f, bodyTop + stroke / 2f),
                size = Size(size.width - stroke, size.height - bodyTop - stroke),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(stroke),
            )
            val inset = stroke + 2.dp.toPx()
            val gap = 2.dp.toPx()
            val innerTop = bodyTop + inset
            val innerHeight = size.height - innerTop - inset
            val cellHeight = (innerHeight - gap * 3) / 4f
            for (index in 0 until 4) {
                val alpha = when {
                    !animate -> if (animation == NowCellAnimation.None) (if (index == 0) 0.25f else 0.8f) else 1f
                    else -> {
                        // CSS: charge delay (3 - i) * 0.25 s, drain delay i * 0.25 s, 1.4 s period.
                        val delayFraction = (if (animation == NowCellAnimation.ChargeWave) 3 - index else index) * 0.25f / 1.4f
                        waveOpacity(((progress - delayFraction) % 1f + 1f) % 1f)
                    }
                }
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(inset, innerTop + index * (cellHeight + gap)),
                    size = Size(size.width - inset * 2, cellHeight),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
        }
    }
}

/** Keyframes 0 %/100 % -> 0.22, 35 % -> 1. */
private fun waveOpacity(phase: Float): Float = if (phase < 0.35f) {
    0.22f + (1f - 0.22f) * (phase / 0.35f)
} else {
    1f - (1f - 0.22f) * ((phase - 0.35f) / 0.65f)
}

@Composable
private fun PowerChartCard(chart: NowChart?) {
    val colors = KmfTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KmfDimens.CardRadius),
        color = colors.surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Power \u00b7 last 15 min", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Spacer(Modifier.weight(1f))
                chart?.let { Text(it.meta, style = KmfTheme.numbers.small, color = colors.textSecondary, maxLines = 1) }
            }
            if (chart == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .dashedBorder(colors.border, 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Chart starts with the first readings", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
            } else {
                PowerChart(chart)
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun PowerChart(chart: NowChart) {
    TrendChart(
        points = chart.points.map { TrendChartPoint(xFraction = it.xFraction, value = it.powerW) },
        yMin = -chart.scaleW,
        yMax = chart.scaleW,
        colorMode = TrendChartColorMode.Signed,
        contentDescription = "Power trend chart, charging above the line, discharging below. ${chart.meta}",
        gapMarker = chart.gapMarker,
    )
}

@Composable
private fun DataLinkCard(link: NowDataLink) {
    val colors = KmfTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KmfDimens.CardRadius),
        color = colors.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Data link", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            LinkTicks(
                ticks = link.compactTicks,
                mode = link.mode,
                fullHeight = 14.dp,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Frames received over the last 30 seconds: ${link.stat}" },
            )
            // Fixed width so the tick strip never shifts when the label changes.
            Text(
                text = link.stat,
                modifier = Modifier.width(DataLinkStatWidth),
                style = KmfTheme.numbers.small,
                color = if (link.statTone == NowTone.Neutral) colors.textSecondary else link.statTone.accent().accent,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
    }
}

private val DataLinkStatWidth = 112.dp

@Composable
private fun LinkTicks(ticks: List<NowLinkTick>, mode: NowLinkMode, fullHeight: Dp, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    val green = colors.live.accent
    val red = colors.error.accent
    val blue = colors.charge.accent
    val idle = colors.border
    Canvas(modifier.height(fullHeight)) {
        if (ticks.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val tickW = (size.width - gap * (ticks.size - 1)) / ticks.size
        val full = size.height
        val missedH = 4.dp.toPx()
        val lowH = 3.dp.toPx()
        ticks.forEachIndexed { index, tick ->
            val recent = index >= ticks.size - 4
            val (height, color) = when (mode) {
                NowLinkMode.Live -> when (tick) {
                    NowLinkTick.Received -> full to if (recent) green else LinkReceivedDim
                    NowLinkTick.Missed -> missedH to red
                    NowLinkTick.Idle -> lowH to idle
                }
                NowLinkMode.Stale -> when (tick) {
                    NowLinkTick.Received -> full to LinkReceivedDim
                    NowLinkTick.Missed -> 4.dp.toPx() to red
                    NowLinkTick.Idle -> lowH to idle
                }
                NowLinkMode.Sync -> when (tick) {
                    NowLinkTick.Received -> full to blue
                    else -> lowH to idle
                }
                NowLinkMode.Offline -> when (tick) {
                    NowLinkTick.Received -> full to LinkOfflineReceived
                    else -> lowH to red.copy(alpha = 0.45f)
                }
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (tickW + gap), full - height),
                size = Size(tickW, height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun TotalsFooter(totalsIn: String, totalsOut: String) {
    val colors = KmfTheme.colors
    val mono = KmfTheme.numbers.small
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Meter totals", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            text = buildAnnotatedString {
                append("in ")
                withStyle(SpanStyle(fontFamily = mono.fontFamily, color = colors.textPrimary)) { append(totalsIn) }
                append(" \u00b7 out ")
                withStyle(SpanStyle(fontFamily = mono.fontFamily, color = colors.textPrimary)) { append(totalsOut) }
                append(" kWh")
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Previews: one per Now tab state, built through the real reducer.
// ---------------------------------------------------------------------------------------------

private const val PREVIEW_NOW_MS = 1_760_000_000_000L
private val PREVIEW_ZONE: ZoneId = ZoneId.of("UTC")

private fun previewSamples(charging: Boolean): List<KmfBatterySampleEntity> = (0 until 90).map { index ->
    val t = PREVIEW_NOW_MS - (89 - index) * 10_000L
    val wobble = kotlin.math.sin(index / 6.0) * 30.0
    val power = if (charging) 40.0 + index * 1.6 + wobble else 30.0 - index * 1.4 + wobble
    KmfBatterySampleEntity(
        timestampMs = t, deviceAddress = "preview", deviceName = "KMF Meter",
        voltageV = 13.28, currentA = power / 13.28, powerW = power, charging = power > 0,
        minutesRemaining = 174, remainingAh = 164.0, capacityAh = 200.0, socPercent = 82.0,
        chargeKwh = 48.31, dischargeKwh = 45.07, hasAFrame = true, hasBFrame = true, hasCFrame = true,
        rawAFieldsCsv = null, rawBFieldsCsv = null, rawCFieldsCsv = null, connectionState = "COLLECTING",
        serviceUuid = null, notifyUuid = null, writeUuid = null,
    )
}

private fun previewFrames(lastMs: Long, count: Int = 58): List<Long> =
    (0 until 60).filter { it != 17 && it != 41 }.take(count).map { lastMs - it * 1_000L }

private fun previewState(
    phase: CollectionPhase,
    currentA: Double = 12.40,
    soc: Double = 82.0,
    frameAgeMs: Long = 2_000L,
    hasValues: Boolean = true,
    configured: Boolean = true,
    nextReconnectAtMs: Long? = null,
    error: String? = null,
): BleUiState {
    val lastFrame = PREVIEW_NOW_MS - frameAgeMs
    return BleUiState(
        selectedDevice = ScannedDevice("KMF Meter", "00:00:00:00:00:00", 0, emptyList()),
        latestReading = if (hasValues) {
            KmfReading(
                voltageV = if (soc < 20) 12.71 else 13.28,
                currentA = currentA,
                powerW = currentA * 13.28,
                charging = currentA > 0,
                minutesRemaining = if (soc < 20) 65 else 174,
                remainingAh = soc * 2.0,
                capacityAh = 200.0,
                socPercent = soc,
                chargeKwh = 48.31,
                dischargeKwh = 45.07,
            )
        } else {
            KmfReading()
        },
        lastReadingTimestampMs = if (hasValues) lastFrame else null,
        hasAFrame = phase == CollectionPhase.BOOTSTRAPPING,
        inboundFrameTimesMs = when (phase) {
            CollectionPhase.BOOTSTRAPPING -> (0 until 8).map { PREVIEW_NOW_MS - it * 1_000L }
            else -> previewFrames(lastFrame)
        },
        autonomousCollectionConfigured = configured,
        collectionConfigurationLoaded = true,
        collectionState = CollectionState(
            phase = phase,
            visibility = CollectionVisibility.FOREGROUND,
            nextReconnectAtMs = nextReconnectAtMs,
            reconnectAttempt = if (nextReconnectAtMs != null) 4 else 0,
            linkDroppedAtMs = if (nextReconnectAtMs != null) lastFrame else null,
            error = error,
        ),
    )
}

@Composable
private fun NowPreview(state: BleUiState, charging: Boolean = true) {
    KmfBleTheme {
        NowScreen(
            model = NowScreenReducer.reduce(state, previewSamples(charging), PREVIEW_NOW_MS, PREVIEW_ZONE),
            contentPadding = PaddingValues(0.dp),
            onOpenDevice = {},
            onBannerAction = {},
        )
    }
}

@Preview(name = "Now - Charging", widthDp = 390, heightDp = 844)
@Composable
private fun NowChargingPreview() = NowPreview(previewState(CollectionPhase.COLLECTING))

@Preview(name = "Now - Discharging", widthDp = 390, heightDp = 844)
@Composable
private fun NowDischargingPreview() = NowPreview(previewState(CollectionPhase.COLLECTING, currentA = -6.66), charging = false)

@Preview(name = "Now - Low battery", widthDp = 390, heightDp = 844)
@Composable
private fun NowLowBatteryPreview() =
    NowPreview(previewState(CollectionPhase.COLLECTING, currentA = -6.66, soc = 14.0), charging = false)

@Preview(name = "Now - First sync", widthDp = 390, heightDp = 844)
@Composable
private fun NowFirstSyncPreview() = NowPreview(previewState(CollectionPhase.BOOTSTRAPPING, hasValues = false))

@Preview(name = "Now - Resyncing", widthDp = 390, heightDp = 844)
@Composable
private fun NowResyncingPreview() = NowPreview(previewState(CollectionPhase.BOOTSTRAPPING, frameAgeMs = 60_000L))

@Preview(name = "Now - Stale data", widthDp = 390, heightDp = 844)
@Composable
private fun NowStalePreview() = NowPreview(previewState(CollectionPhase.COLLECTING, frameAgeMs = 15_000L))

@Preview(name = "Now - Reconnecting", widthDp = 390, heightDp = 844)
@Composable
private fun NowReconnectingPreview() = NowPreview(
    previewState(CollectionPhase.ERROR, frameAgeMs = 40_000L, nextReconnectAtMs = PREVIEW_NOW_MS + 15_000L),
)

@Preview(name = "Now - Out of range", widthDp = 390, heightDp = 844)
@Composable
private fun NowOutOfRangePreview() = NowPreview(previewState(CollectionPhase.WAITING_FOR_DEVICE, frameAgeMs = 120_000L))

@Preview(name = "Now - Collection error", widthDp = 390, heightDp = 844)
@Composable
private fun NowCollectionErrorPreview() = NowPreview(
    previewState(CollectionPhase.ERROR, frameAgeMs = 120_000L, error = "Could not turn on meter notifications"),
)

@Preview(name = "Now - Collection off", widthDp = 390, heightDp = 844)
@Composable
private fun NowCollectionOffPreview() =
    NowPreview(previewState(CollectionPhase.STOPPED, frameAgeMs = 600_000L, configured = false))

@Preview(name = "Now - Large font", widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun NowLargeFontPreview() = NowPreview(previewState(CollectionPhase.COLLECTING))
