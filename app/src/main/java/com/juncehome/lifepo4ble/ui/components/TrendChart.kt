package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import kotlin.math.abs

/** One chart point; [xFraction] 0 = window start, 1 = window end. */
@Immutable
data class TrendChartPoint(
    val xFraction: Float,
    val value: Double,
    /** Starts a new line segment (no line is drawn across a data gap). */
    val startsSegment: Boolean = false,
)

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
 * - [scrubIndex] / [onScrub]: vertical scrub line and dot; tap or horizontal drag reports the
 *   nearest point index (vertical scrolling of the page still works).
 */
@Composable
fun TrendChart(
    points: List<TrendChartPoint>,
    yMin: Double,
    yMax: Double,
    colorMode: TrendChartColorMode,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 80.dp,
    verticalPadding: Dp = 4.dp,
    gridLines: Boolean = false,
    endDot: Boolean = true,
    gapMarker: Boolean = false,
    scrubIndex: Int? = null,
    onScrub: ((Int) -> Unit)? = null,
) {
    val colors = KmfTheme.colors
    val up = colors.charge.accent
    val down = if (colorMode == TrendChartColorMode.Signed) colors.discharge.accent else up
    val fillAlpha = if (colorMode == TrendChartColorMode.Signed) 0.16f else 0.10f
    val zeroLine = colors.border
    val grid = colors.hairline
    val marker = colors.error.accent
    val surface = colors.surface
    val scrubLine = colors.textSecondary
    val scrubDot = colors.textPrimary
    val currentPoints = rememberUpdatedState(points)
    val currentOnScrub = rememberUpdatedState(onScrub)

    val touch = if (onScrub != null) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    report(currentPoints.value, currentOnScrub.value, offset.x, size.width.toFloat(), 4.dp.toPx())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        report(currentPoints.value, currentOnScrub.value, offset.x, size.width.toFloat(), 4.dp.toPx())
                    },
                ) { change, _ ->
                    change.consume()
                    report(currentPoints.value, currentOnScrub.value, change.position.x, size.width.toFloat(), 4.dp.toPx())
                }
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { this.contentDescription = contentDescription }
            .then(touch),
    ) {
        if (points.isEmpty()) return@Canvas
        val endPad = 4.dp.toPx()
        val vPad = verticalPadding.toPx()
        val usableW = size.width - endPad
        val plotTop = vPad
        val plotBottom = size.height - vPad
        val plotH = plotBottom - plotTop
        fun x(point: TrendChartPoint) = point.xFraction.coerceIn(0f, 1f) * usableW
        fun y(value: Double) = plotTop + TrendChartMath.yFraction(value, yMin, yMax) * plotH

        if (gridLines) {
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
                start = Offset(0f, baseY),
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
