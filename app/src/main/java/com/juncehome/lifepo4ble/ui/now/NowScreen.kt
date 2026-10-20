package com.juncehome.lifepo4ble.ui.now

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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
import com.juncehome.lifepo4ble.ui.components.ConnectionPill
import com.juncehome.lifepo4ble.ui.components.screenHeader
import com.juncehome.lifepo4ble.ui.components.accent
import com.juncehome.lifepo4ble.ui.components.TrendChart
import com.juncehome.lifepo4ble.ui.components.TrendChartColorMode
import com.juncehome.lifepo4ble.ui.components.TrendChartMath
import com.juncehome.lifepo4ble.ui.components.TrendChartYAxisInset
import com.juncehome.lifepo4ble.ui.components.TrendChartPoint
import com.juncehome.lifepo4ble.ui.components.ValueWithUnit
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import java.time.ZoneId
import kotlinx.coroutines.delay

private const val TAG = "KMF-BLE"

/** Data link tick colors from the Now board that are not general theme tokens. */
private val LinkReceivedDim = Color(0xFF2E5E4A)
private val LinkRailTrack = Color(0xFF262B33)

/** Orbit hero geometry and the app-logo colors (ic_launcher_foreground.xml). */
private val OrbitRingAreaHeight = 254.dp
private val OrbitCenterY = 128.dp
private val OrbitRadius = 106.dp
private val OrbitStroke = 20.dp
private val OrbitGlowRadius = 138.dp
private val OrbitBoltSize = 20.dp
/** Angle between a comet's head and each trailing dot, wide enough to stay distinct in motion. */
private const val OrbitTrailDegrees = 7f
/** A new comet speed or count must hold this long before the orbit follows it. */
private const val OrbitSettleMs = 2_000L
private val OrbitTrack = Color(0xFF24394A)
private val OrbitGradientStart = Color(0xFF19D3A2)
private val OrbitGradientEnd = Color(0xFFC6F84A)
private val OrbitBoltIdle = Color(0xFF3A414B)
private val OrbitBoltPath = Path().apply {
    moveTo(57f, 38f)
    lineTo(45f, 56f)
    lineTo(53f, 56f)
    lineTo(50f, 71f)
    lineTo(63f, 51f)
    lineTo(55f, 51f)
    close()
}

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
    /** Power chart tap: opens the Trends tab on the live power view. */
    onOpenTrends: () -> Unit,
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
                "link='${model.dataLink.stat}' linkRail=${model.dataLink.rail} banner='${model.banner?.title}' " +
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
        onOpenTrends = onOpenTrends,
    )
}

@Composable
fun NowScreen(
    model: NowScreenState,
    contentPadding: PaddingValues,
    onOpenDevice: () -> Unit,
    onBannerAction: (NowBannerAction) -> Unit,
    onOpenTrends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KmfTheme.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(contentPadding),
    ) {
        // The scrolling column is at least one viewport tall, so the power card can take the
        // remaining height and the Data link sits at the bottom. Taller content still scrolls.
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(start = KmfDimens.ScreenPadding, end = KmfDimens.ScreenPadding, top = KmfDimens.ScreenPadding, bottom = 16.dp),
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
            OrbitHero(model)
            PowerChartCard(model.chart, onOpenTrends = onOpenTrends, modifier = Modifier.weight(1f))
            DataLinkCard(model.dataLink)
        }
    }
}

@Composable
private fun NowHeader(
    meterName: String,
    pill: NowPill,
    onOpenDevice: () -> Unit,
    onPillAction: (NowBannerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KmfTheme.colors
    Row(
        modifier = modifier.screenHeader(),
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
        ConnectionPill(pill = pill, onAction = onPillAction, logSource = "Now header")
    }
}

/**
 * Orbit cell hero (canvas page "Now · Orbit cell (locked)"): a logo ring filled to SoC with
 * comets circling it, SoC and time left inside, Ah under it, then Power / Current / Voltage.
 * Every slot has a fixed size so nothing moves between values or states (see AGENTS.md).
 */
@Composable
private fun OrbitHero(model: NowScreenState, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    val flow = model.flow
    val description = buildString {
        append("State of charge ${model.soc} percent, ${flow.eta}, ")
        append("${model.ahRemaining} of ${model.ahCapacity} amp hours, ")
        append("power ${flow.power} watts, current ${flow.current} amps, ${model.voltage} volts")
        model.lowBatteryDetail?.let { append(". Low battery. $it") }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        OrbitRing(model, Modifier.fillMaxWidth().height(OrbitRingAreaHeight))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.textPrimary)) { append(model.ahRemaining) }
                append(" / ${model.ahCapacity} Ah")
            },
            style = KmfTheme.numbers.small,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )
        Spacer(Modifier.height(6.dp))
        StatStrip(model)
    }
}

@Composable
private fun OrbitRing(model: NowScreenState, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    val flow = model.flow
    // Live power wobbles: let speed and comet count settle before the orbit follows a change.
    val period = rememberSettled(flow.orbitPeriodMs)
    val cometCount = rememberSettled(flow.cometCount)
    val moving = period != null && !KmfTheme.reduceMotion
    val direction = if (flow.animation == NowCellAnimation.DrainWave) -1f else 1f
    val turn = if (moving) {
        val transition = rememberInfiniteTransition(label = "orbit")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
            label = "orbitTurn",
        )
        value
    } else {
        0f
    }
    val lowColor = colors.error.accent
    val flowAccent = flow.tone.accent().accent
    val glowColor = when {
        period == null -> Color.Transparent
        model.lowBattery -> lowColor
        else -> flowAccent
    }
    val boltColor = when {
        !model.hasValues -> OrbitBoltIdle
        period != null && flow.animation == NowCellAnimation.ChargeWave -> colors.textPrimary
        else -> colors.textTertiary
    }
    val fraction = model.socFraction
    val showComets = period != null
    // Ring text is display type: keep its size fixed so large font settings cannot push it into the ring.
    val fontScale = LocalDensity.current.fontScale
    val textScale = if (fontScale > 1f) 1f / fontScale else 1f
    val socStyle = KmfTheme.numbers.orbitSoc.let { it.copy(fontSize = it.fontSize * textScale, lineHeight = it.lineHeight * textScale) }
    val socUnitStyle = KmfTheme.numbers.orbitSocUnit.let { it.copy(fontSize = it.fontSize * textScale) }
    val etaStyle = MaterialTheme.typography.bodySmall.let {
        it.copy(fontSize = 12.sp * textScale, lineHeight = 16.sp * textScale)
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, OrbitCenterY.toPx())
            val radius = OrbitRadius.toPx()
            val stroke = OrbitStroke.toPx()
            if (glowColor != Color.Transparent) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.24f), glowColor.copy(alpha = 0f)),
                        center = center,
                        radius = OrbitGlowRadius.toPx(),
                    ),
                    radius = OrbitGlowRadius.toPx(),
                    center = center,
                )
            }
            drawCircle(OrbitTrack, radius = radius, center = center, style = Stroke(stroke))
            if (model.hasValues && fraction > 0f) {
                val ringBrush = if (model.lowBattery) {
                    SolidColor(lowColor)
                } else {
                    Brush.linearGradient(
                        colors = listOf(OrbitGradientStart, OrbitGradientEnd),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius),
                    )
                }
                drawArc(
                    brush = ringBrush,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceAtMost(0.9999f),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            if (showComets) {
                val dotRadius = 3.2.dp.toPx()
                for (comet in 0 until cometCount) {
                    val head = comet * (360f / cometCount) - 8f + turn * direction
                    for (trail in 0..3) {
                        val angle = Math.toRadians((head - trail * OrbitTrailDegrees * direction).toDouble())
                        drawCircle(
                            color = Color.White.copy(alpha = if (trail == 0) 0.95f else 0.6f - trail * 0.15f),
                            radius = dotRadius - trail * 0.7.dp.toPx(),
                            center = Offset(
                                center.x + radius * kotlin.math.sin(angle).toFloat(),
                                center.y - radius * kotlin.math.cos(angle).toFloat(),
                            ),
                        )
                    }
                }
            }
            // Logo bolt (108-unit icon grid), centered 58 dp above the ring center.
            val boltScale = OrbitBoltSize.toPx() / 33f
            translate(center.x - 54f * boltScale, center.y - 58.dp.toPx() - 54.5f * boltScale) {
                scale(boltScale, boltScale, pivot = Offset.Zero) {
                    drawPath(OrbitBoltPath, boltColor)
                }
            }
        }
        val dot = model.soc.indexOf('.')
        val whole = if (dot >= 0) model.soc.substring(0, dot) else model.soc
        val fractionText = if (dot >= 0) model.soc.substring(dot) else ""
        val unitSpan = SpanStyle(
            fontFamily = socUnitStyle.fontFamily,
            fontWeight = socUnitStyle.fontWeight,
            fontSize = socUnitStyle.fontSize,
            fontFeatureSettings = socUnitStyle.fontFeatureSettings,
            letterSpacing = socUnitStyle.letterSpacing,
        )
        Text(
            text = buildAnnotatedString {
                append(whole)
                withStyle(unitSpan) { append(fractionText) }
                withStyle(unitSpan.copy(color = colors.textSecondary)) { append("%") }
            },
            style = socStyle,
            color = if (model.lowBattery) colors.error.onTint else colors.textPrimary,
            maxLines = 1,
            // Measured on device: a 72 sp line box 48.5 dp above the ring center puts the digits' ink on it.
            modifier = Modifier.offset(y = OrbitCenterY - 48.5.dp),
        )
        Text(
            text = flow.eta,
            style = etaStyle,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(y = OrbitCenterY + 41.dp)
                .width(144.dp)
                .height(32.dp),
        )
    }
}

/**
 * [value] once it has held for [OrbitSettleMs]; the first value and a switch to or from null
 * (orbit starting or stopping) apply at once.
 */
@Composable
private fun <T> rememberSettled(value: T): T {
    var settled by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (settled != null && value != null) delay(OrbitSettleMs)
        settled = value
    }
    return settled
}

@Composable
private fun StatStrip(model: NowScreenState) {
    val colors = KmfTheme.colors
    val flow = model.flow
    val powerColor = when {
        !model.hasValues -> colors.textSecondary
        model.displayState.isOffline -> colors.textSoft
        model.lowBattery -> colors.error.accent
        flow.tone == NowTone.Muted -> colors.textSecondary
        else -> flow.tone.accent().accent
    }
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(KmfDimens.CardRadius),
        color = colors.surface,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StatCell("POWER", flow.power, "W", powerColor, Modifier.weight(1f))
            StatDivider()
            StatCell("CURRENT", flow.current, "A", colors.textPrimary, Modifier.weight(1f))
            StatDivider()
            StatCell("VOLTAGE", model.voltage, "V", colors.textPrimary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(KmfDimens.Hairline)
            .background(KmfTheme.colors.hairline),
    )
}

@Composable
private fun StatCell(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
            color = KmfTheme.colors.textTertiary,
            maxLines = 1,
        )
        ValueWithUnit(
            value = value,
            unit = unit,
            valueStyle = KmfTheme.numbers.medium.copy(fontSize = 18.sp, lineHeight = 22.sp),
            unitStyle = KmfTheme.numbers.mediumUnit.copy(fontSize = 11.sp),
            valueColor = valueColor,
        )
    }
}

@Composable
private fun PowerChartCard(chart: NowChart?, onOpenTrends: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KmfDimens.CardRadius))
            .clickable(
                role = Role.Button,
                onClickLabel = "Open Trends",
                onClick = {
                    AppLog.d("Now chart tapped, opening Trends", TAG)
                    onOpenTrends()
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Power, last 15 minutes. Open Trends"
            },
        shape = RoundedCornerShape(KmfDimens.CardRadius),
        color = colors.surface,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Fixed-height row in every state: min on the left, max on the right, empty before data.
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chart?.let { "min ${it.minLabel}" }.orEmpty(), style = KmfTheme.numbers.small, color = colors.textSecondary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(chart?.let { "max ${it.maxLabel}" }.orEmpty(), style = KmfTheme.numbers.small, color = colors.textSecondary, maxLines = 1)
            }
            if (chart == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = PowerChartMinHeight)
                        .dashedBorder(colors.border, 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Chart starts with the first readings", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
            } else {
                PowerChart(chart, Modifier.weight(1f))
            }
        }
    }
}

/** Smallest plot height for the Now power chart, so it never collapses on short screens. */
private val PowerChartMinHeight: Dp = 160.dp

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun PowerChart(chart: NowChart, modifier: Modifier) {
    val colors = KmfTheme.colors
    val ticks = remember(chart.scaleW) { TrendChartMath.yTicks(-chart.scaleW, chart.scaleW) }
    val xLabelStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
    Column(modifier = modifier) {
        TrendChart(
            points = chart.points.map { TrendChartPoint(xFraction = it.xFraction, value = it.powerW) },
            yMin = -chart.scaleW,
            yMax = chart.scaleW,
            colorMode = TrendChartColorMode.Signed,
            contentDescription = "Power trend chart, charging above the line, discharging below. Min ${chart.minLabel}, max ${chart.maxLabel}",
            modifier = Modifier.weight(1f),
            height = null,
            verticalPadding = 8.dp,
            yTicks = ticks,
            gapMarker = chart.gapMarker,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TrendChartYAxisInset, end = 4.dp),
        ) {
            Text("\u221215 min", style = xLabelStyle, color = colors.textTertiary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("now", style = xLabelStyle, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun DataLinkCard(link: NowDataLink) {
    val colors = KmfTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().height(DataLinkHeight),
        shape = RoundedCornerShape(DataLinkRadius),
        color = colors.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Data link", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            val statColor = if (link.statTone == NowTone.Neutral) colors.textSecondary else link.statTone.accent().accent
            // Ticks and rail both run right up to the state text, which keeps its right edge
            // (user choice: the strip follows the label length instead of a fixed slot).
            val rail = link.rail
            if (rail == null) {
                LinkTicks(
                    ticks = link.compactTicks,
                    mode = link.mode,
                    fullHeight = 14.dp,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Frames received over the last 30 seconds: ${link.stat}" },
                )
            } else {
                LinkRail(
                    rail = rail,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Data link: ${link.stat}" },
                )
            }
            Text(
                text = link.stat,
                style = KmfTheme.numbers.small,
                color = statColor,
                maxLines = 1,
            )
        }
    }
}

private val DataLinkHeight = 32.dp
private val DataLinkRadius = 12.dp

/**
 * Full-height ticks only, so the strip never shows small dots. Received seconds are green (dim
 * except the last 4 s; dim only when stale), missed seconds are faint red bars, and seconds with no
 * history yet (before the first frame) show the rail track, so bars fill in from the right.
 */
@Composable
private fun LinkTicks(ticks: List<NowLinkTick>, mode: NowLinkMode, fullHeight: Dp, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    val green = colors.live.accent
    val missed = colors.error.accent.copy(alpha = LinkMissedAlpha)
    val blue = colors.charge.accent
    Canvas(modifier.height(fullHeight)) {
        if (ticks.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val step = (size.width + gap) / ticks.size
        val tickW = step - gap
        val full = size.height
        val trackH = LinkRailHeight.toPx()
        var index = 0
        while (index < ticks.size) {
            if (ticks[index] == NowLinkTick.Idle) {
                // One track piece per run of seconds without history, joined across the gaps.
                var end = index
                while (end + 1 < ticks.size && ticks[end + 1] == NowLinkTick.Idle) end++
                drawRoundRect(
                    color = LinkRailTrack,
                    topLeft = Offset(index * step, (full - trackH) / 2f),
                    size = Size((end - index + 1) * step - gap, trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
                index = end + 1
                continue
            }
            val recent = index >= ticks.size - 4
            val color = when {
                ticks[index] == NowLinkTick.Missed -> missed
                mode == NowLinkMode.Sync -> blue
                mode == NowLinkMode.Live && recent -> green
                else -> LinkReceivedDim
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * step, 0f),
                size = Size(tickW, full),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            index++
        }
    }
}

private const val LinkMissedAlpha = 0.35f

/**
 * One 4 dp rail across the free width. A soft glow sweeps back and forth while the app gets the
 * link going by itself ([NowLinkRail.Syncing] blue, [NowLinkRail.Retrying] fast amber,
 * [NowLinkRail.Waiting] slow grey) and parks in
 * the middle under reduced motion; error and stopped rails stand still.
 */
@Composable
private fun LinkRail(rail: NowLinkRail, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    val sweepMs = when (rail) {
        NowLinkRail.Syncing -> LinkRailSyncSweepMs
        NowLinkRail.Retrying -> LinkRailFastSweepMs
        NowLinkRail.Waiting -> LinkRailSlowSweepMs
        NowLinkRail.Error, NowLinkRail.Stopped -> null
    }
    val track = when (rail) {
        NowLinkRail.Syncing, NowLinkRail.Retrying, NowLinkRail.Waiting -> LinkRailTrack
        NowLinkRail.Error -> colors.error.accent.copy(alpha = 0.55f)
        NowLinkRail.Stopped -> colors.border
    }
    val glow = when (rail) {
        NowLinkRail.Syncing -> colors.charge.accent
        NowLinkRail.Retrying -> colors.discharge.accent
        else -> colors.textSecondary
    }
    val position = when {
        sweepMs == null -> null
        KmfTheme.reduceMotion -> 0.5f
        else -> {
            val transition = rememberInfiniteTransition(label = "linkRail")
            val value by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(sweepMs, easing = EaseInOut), RepeatMode.Reverse),
                label = "linkRailSweep",
            )
            value
        }
    }
    Canvas(modifier.height(14.dp)) {
        val railH = LinkRailHeight.toPx()
        val top = (size.height - railH) / 2f
        val corner = CornerRadius(railH / 2f)
        drawRoundRect(color = track, topLeft = Offset(0f, top), size = Size(size.width, railH), cornerRadius = corner)
        if (position != null) {
            val glowW = LinkRailGlowWidth.toPx().coerceAtMost(size.width)
            val x = (size.width - glowW) * position
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, glow, Color.Transparent), startX = x, endX = x + glowW),
                topLeft = Offset(x, top),
                size = Size(glowW, railH),
                cornerRadius = corner,
            )
        }
    }
}

private const val LinkRailSyncSweepMs = 2_000
private const val LinkRailFastSweepMs = 1_400
private const val LinkRailSlowSweepMs = 2_800
private val LinkRailGlowWidth = 22.dp
private val LinkRailHeight = 4.dp

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
            onOpenTrends = {},
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
