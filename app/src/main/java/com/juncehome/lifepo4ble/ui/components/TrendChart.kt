package com.juncehome.lifepo4ble.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One chart point; [xFraction] 0 = window start, 1 = window end. */
@Immutable
data class TrendChartPoint(
    val xFraction: Float,
    val value: Double,
    /** Starts a new line segment (no line is drawn across a data gap). */
    val startsSegment: Boolean = false,
)

/** One Y-axis tick: [value] in the chart's units, [label] is the unsigned magnitude. */
@Immutable
data class TrendChartTick(val value: Double, val label: String)

/** Minimum width reserved on the left of a chart that draws Y-axis ticks (label column plus gap). */
val TrendChartYAxisInset: Dp = 22.dp

/** Minimum width of the right-aligned Y-tick label column inside [TrendChartYAxisInset]. */
private val TrendChartYAxisLabelWidth: Dp = 18.dp

/** Gap between the Y-tick label column and the plot. */
private val TrendChartYAxisGap: Dp = TrendChartYAxisInset - TrendChartYAxisLabelWidth

@Composable
private fun trendChartAxisLabelStyle(): TextStyle =
    KmfTheme.numbers.small.copy(fontSize = 10.sp, lineHeight = 12.sp)

/**
 * Left inset of a chart that draws [yTicks]: the widest measured label plus the gap, never less
 * than [TrendChartYAxisInset]. Use the same value to pad rows aligned with the plot (X labels).
 * Returns 0 when [yTicks] is null.
 */
@Composable
fun rememberTrendChartAxisInset(yTicks: List<TrendChartTick>?): Dp {
    if (yTicks == null) return 0.dp
    val measurer = rememberTextMeasurer()
    val style = trendChartAxisLabelStyle()
    val density = LocalDensity.current
    return remember(yTicks, measurer, style, density) {
        val widest = yTicks.maxOfOrNull { measurer.measure(it.label, style).size.width } ?: 0
        val label = with(density) { widest.toDp() }
        maxOf(TrendChartYAxisLabelWidth, label) + TrendChartYAxisGap
    }
}

/** How the line is colored. */
enum class TrendChartColorMode {
    /** Blue above zero, amber below, area filled to a dashed zero line (Power, Current). */
    Signed,

    /** Single blue line, area filled to the bottom (SoC, Voltage). */
    Single,
}

/** Pure geometry helpers shared by the chart and its touch handling. */
object TrendChartMath {
    /** Index of the point whose x is nearest to [fraction], or null for an empty list. Points must be sorted by x. */
    fun nearestIndex(points: List<TrendChartPoint>, fraction: Float): Int? {
        if (points.isEmpty()) return null
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val mid = (low + high) / 2
            if (points[mid].xFraction < fraction) low = mid + 1 else high = mid
        }
        if (low > 0 && abs(points[low - 1].xFraction - fraction) <= abs(points[low].xFraction - fraction)) {
            return low - 1
        }
        return low
    }

    private val yTickMantissas = doubleArrayOf(1.0, 2.0, 5.0)
    private const val MAX_Y_TICK_INTERVALS = 4
    private const val MAX_Y_TICK_DECADES = 9

    /**
     * Smallest "nice" step (1, 2 or 5 times a power of ten, at least 1) with at most
     * [MAX_Y_TICK_INTERVALS] intervals across the range, so even a +-1 W scale gets ticks.
     */
    fun yTickStep(yMin: Double, yMax: Double): Double {
        val span = yMax - yMin
        var decade = 1.0
        repeat(MAX_Y_TICK_DECADES) {
            yTickMantissas.forEach { mantissa ->
                val step = mantissa * decade
                if (span > 0.0 && span / step <= MAX_Y_TICK_INTERVALS) return step
            }
            decade *= 10.0
        }
        return decade
    }

    /**
     * Ticks at multiples of [yTickStep] inside [yMin, yMax]. Labels are the absolute value with no
     * sign, so charging and discharging sides read the same. Empty for an empty or invalid range.
     */
    fun yTicks(yMin: Double, yMax: Double): List<TrendChartTick> {
        val span = yMax - yMin
        if (!(span > 0.0) || span.isInfinite()) return emptyList()
        val step = yTickStep(yMin, yMax)
        val first = ceil(yMin / step).toLong()
        val last = floor(yMax / step).toLong()
        if (last < first) return emptyList()
        return (first..last).map { index ->
            val value = index * step
            TrendChartTick(value = value, label = abs(value).roundToLong().toString())
        }
    }

    private val niceStepMantissas = doubleArrayOf(1.0, 2.0, 2.5, 5.0)
    private const val NICE_MIN_DECADE = -3
    private const val NICE_MAX_DECADE = 9

    /**
     * Smallest "nice" step (1, 2, 2.5 or 5 times a power of ten, fractions allowed down to 0.001)
     * giving at most [maxIntervals] intervals across [yMin, yMax]; 0.0 for an empty range.
     */
    fun niceYTickStep(yMin: Double, yMax: Double, maxIntervals: Int = MAX_Y_TICK_INTERVALS): Double {
        val span = yMax - yMin
        if (!(span > 0.0) || span.isInfinite()) return 0.0
        for (decade in NICE_MIN_DECADE..NICE_MAX_DECADE) {
            val scale = 10.0.pow(decade)
            for (mantissa in niceStepMantissas) {
                val step = mantissa * scale
                if (span / step <= maxIntervals + 1e-9) return step
            }
        }
        return 10.0.pow(NICE_MAX_DECADE)
    }

    /** Number of decimals needed to print multiples of [step] exactly (0..4). */
    fun decimalsForStep(step: Double): Int {
        if (!(step > 0.0)) return 0
        for (decimals in 0..4) {
            val scaled = step * 10.0.pow(decimals)
            if (abs(scaled - scaled.roundToInt()) < 1e-6) return decimals
        }
        return 4
    }

    /**
     * Ticks at multiples of [niceYTickStep] inside [yMin, yMax] with labels in the absolute value
     * (no sign) and the decimals implied by the step, e.g. "13.2", "0.25", "500".
     */
    fun niceYTicks(yMin: Double, yMax: Double, maxIntervals: Int = MAX_Y_TICK_INTERVALS): List<TrendChartTick> {
        val step = niceYTickStep(yMin, yMax, maxIntervals)
        if (step <= 0.0) return emptyList()
        val first = ceil(yMin / step - 1e-9).toLong()
        val last = floor(yMax / step + 1e-9).toLong()
        if (last < first) return emptyList()
        val decimals = decimalsForStep(step)
        return (first..last).map { index ->
            val value = index * step
            TrendChartTick(value = value, label = String.format(java.util.Locale.US, "%.${decimals}f", abs(value)))
        }
    }

    /** Vertical position 0 (top) .. 1 (bottom) of [value] in [yMin, yMax]. */
    fun yFraction(value: Double, yMin: Double, yMax: Double): Float {
        val span = yMax - yMin
        if (span <= 0.0 || span.isNaN()) return 0.5f
        return (1.0 - (value - yMin) / span).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * Canvas line/area chart used by the Now power chart and the Trends chart.
 *
 * - [colorMode] Signed: zero line at value 0, line and 16 % area in charge color above and
 *   discharge color below. Single: one charge-colored line with a 10 % area to the bottom.
 * - [gridLines]: horizontal hairlines at top, middle and bottom of the plot area.
 * - [endDot]: dot at the last point, sign-colored in Signed mode.
 * - [gapMarker]: red dashed vertical marker at the last point (data stopped).
 * - [onScrubClear]: a tap while [scrubIndex] is set hides the scrub instead of moving it.
 * - [scrubIndex] / [onScrub]: vertical scrub line and dot; tap or horizontal drag reports the
 *   nearest point index (vertical scrolling of the page still works).
 * - [yTicks]: left-side tick labels with faint grid lines at each tick (replaces [gridLines]);
 *   reserves the measured label width (at least [TrendChartYAxisInset]) on the left, see
 *   [rememberTrendChartAxisInset].
 * - [height] null: fill the height given by the parent (use weight in a Column).
 * - [endHalo]: pulses a halo around the end dot to show the line is still growing (off with reduce motion).
 */
@Composable
fun TrendChart(
    points: List<TrendChartPoint>,
    yMin: Double,
    yMax: Double,
    colorMode: TrendChartColorMode,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp? = 80.dp,
    verticalPadding: Dp = 4.dp,
    gridLines: Boolean = false,
    yTicks: List<TrendChartTick>? = null,
    endDot: Boolean = true,
    endHalo: Boolean = false,
    gapMarker: Boolean = false,
    scrubIndex: Int? = null,
    onScrub: ((Int) -> Unit)? = null,
    onScrubClear: (() -> Unit)? = null,
) {
    val colors = KmfTheme.colors
    val up = colors.charge.accent
    val down = if (colorMode == TrendChartColorMode.Signed) colors.discharge.accent else up
    val fillAlpha = if (colorMode == TrendChartColorMode.Signed) 0.16f else 0.10f
    val zeroLine = if (yTicks != null) colors.textTertiary else colors.border
    val axisLabelColor = colors.textTertiary
    val axisLabelStyle: TextStyle = trendChartAxisLabelStyle()
    val textMeasurer = rememberTextMeasurer()
    val axisInsetDp = rememberTrendChartAxisInset(yTicks)
    val grid = colors.hairline
    val marker = colors.error.accent
    val surface = colors.surface
    val scrubLine = colors.textSecondary
    val scrubDot = colors.textPrimary
    val currentPoints = rememberUpdatedState(points)
    val currentOnScrub = rememberUpdatedState(onScrub)
    val currentOnScrubClear = rememberUpdatedState(onScrubClear)
    val currentScrubIndex = rememberUpdatedState(scrubIndex)
    val currentInsetDp = rememberUpdatedState(axisInsetDp)
    val halo = if (endHalo && endDot && !KmfTheme.reduceMotion) rememberEndHaloProgress() else null

    val touch = if (onScrub != null) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val clear = currentOnScrubClear.value
                    if (currentScrubIndex.value != null && clear != null) {
                        clear()
                        return@detectTapGestures
                    }
                    val inset = currentInsetDp.value.toPx()
                    report(currentPoints.value, currentOnScrub.value, offset.x - inset, size.width - inset, 4.dp.toPx())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val inset = currentInsetDp.value.toPx()
                        report(currentPoints.value, currentOnScrub.value, offset.x - inset, size.width - inset, 4.dp.toPx())
                    },
                ) { change, _ ->
                    change.consume()
                    val inset = currentInsetDp.value.toPx()
                    report(currentPoints.value, currentOnScrub.value, change.position.x - inset, size.width - inset, 4.dp.toPx())
                }
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .let { if (height != null) it.height(height) else it }
            .semantics { this.contentDescription = contentDescription }
            .then(touch),
    ) {
        if (points.isEmpty()) return@Canvas
        val axisInset = axisInsetDp.toPx()
        val endPad = 4.dp.toPx()
        val vPad = verticalPadding.toPx()
        val usableW = size.width - axisInset - endPad
        val plotTop = vPad
        val plotBottom = size.height - vPad
        val plotH = plotBottom - plotTop
        fun x(point: TrendChartPoint) = axisInset + point.xFraction.coerceIn(0f, 1f) * usableW
        fun y(value: Double) = plotTop + TrendChartMath.yFraction(value, yMin, yMax) * plotH

        if (yTicks != null) {
            yTicks.forEach { tick ->
                val gy = y(tick.value)
                drawLine(grid, Offset(axisInset, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
            }
            val labelWidth = axisInset - TrendChartYAxisGap.toPx()
            yTicks.forEach { tick ->
                val layout = textMeasurer.measure(tick.label, axisLabelStyle)
                drawText(
                    layout,
                    color = axisLabelColor,
                    topLeft = Offset(labelWidth - layout.size.width, y(tick.value) - layout.size.height / 2f),
                )
            }
        } else if (gridLines) {
            listOf(plotTop, plotTop + plotH / 2f, plotBottom).forEach { gy ->
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
            }
        }

        val baseY = if (colorMode == TrendChartColorMode.Signed) y(0.0) else size.height
        val segments = segmentsOf(points)
        val line = Path()
        val area = Path()
        segments.forEach { segment ->
            segment.forEachIndexed { index, point ->
                if (index == 0) line.moveTo(x(point), y(point.value)) else line.lineTo(x(point), y(point.value))
            }
            if (segment.size > 1) {
                area.moveTo(x(segment.first()), baseY)
                segment.forEach { point -> area.lineTo(x(point), y(point.value)) }
                area.lineTo(x(segment.last()), baseY)
                area.close()
            }
        }
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (colorMode == TrendChartColorMode.Signed) {
            clipRect(bottom = baseY) {
                drawSeries(area, line, segments, up, fillAlpha, stroke, ::x, ::y)
            }
            clipRect(top = baseY) {
                drawSeries(area, line, segments, down, fillAlpha, stroke, ::x, ::y)
            }
            drawLine(
                color = zeroLine,
                start = Offset(axisInset, baseY),
                end = Offset(size.width, baseY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
            )
        } else {
            drawSeries(area, line, segments, up, fillAlpha, stroke, ::x, ::y)
        }

        val end = points.last()
        if (gapMarker) {
            drawLine(
                color = marker,
                start = Offset(x(end), vPad),
                end = Offset(x(end), size.height - vPad),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
        }
        if (endDot && scrubIndex == null) {
            val endColor = if (end.value < 0.0) down else up
            halo?.value?.let { progress ->
                drawCircle(
                    color = endColor.copy(alpha = 0.6f * (1f - progress)),
                    radius = 4.dp.toPx() + 7.dp.toPx() * progress,
                    center = Offset(x(end), y(end.value)),
                )
            }
            drawCircle(surface, radius = 6.dp.toPx(), center = Offset(x(end), y(end.value)))
            drawCircle(endColor, radius = 4.dp.toPx(), center = Offset(x(end), y(end.value)))
        }
        scrubIndex?.let { index ->
            val point = points.getOrNull(index) ?: return@let
            val sx = x(point)
            drawLine(scrubLine, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1.dp.toPx())
            drawCircle(surface, radius = 6.5.dp.toPx(), center = Offset(sx, y(point.value)))
            drawCircle(scrubDot, radius = 4.5.dp.toPx(), center = Offset(sx, y(point.value)))
        }
    }
}

/** 0..1 halo growth over 1.4 s, then a 0.6 s rest; same rhythm as the live status pulse. */
@Composable
private fun rememberEndHaloProgress(): State<Float> =
    rememberInfiniteTransition(label = "endHalo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2_000
                0f at 0 using LinearEasing
                1f at 1_400 using LinearEasing
                1f at 2_000
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "endHaloProgress",
    )

/** [x] and [width] are plot-relative (axis inset already removed). */
private fun report(
    points: List<TrendChartPoint>,
    onScrub: ((Int) -> Unit)?,
    x: Float,
    width: Float,
    endPad: Float,
) {
    val usable = (width - endPad).coerceAtLeast(1f)
    val index = TrendChartMath.nearestIndex(points, (x / usable).coerceIn(0f, 1f)) ?: return
    onScrub?.invoke(index)
}

private fun segmentsOf(points: List<TrendChartPoint>): List<List<TrendChartPoint>> {
    val segments = mutableListOf<MutableList<TrendChartPoint>>()
    points.forEach { point ->
        if (segments.isEmpty() || point.startsSegment) segments += mutableListOf(point) else segments.last() += point
    }
    return segments
}

private fun DrawScope.drawSeries(
    area: Path,
    line: Path,
    segments: List<List<TrendChartPoint>>,
    color: Color,
    fillAlpha: Float,
    stroke: Stroke,
    x: (TrendChartPoint) -> Float,
    y: (Double) -> Float,
) {
    drawPath(area, color.copy(alpha = fillAlpha))
    drawPath(line, color, style = stroke)
    // Isolated readings between gaps have no line; draw them as small dots.
    segments.filter { it.size == 1 }.forEach { segment ->
        val point = segment.first()
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(x(point), y(point.value)))
    }
}
