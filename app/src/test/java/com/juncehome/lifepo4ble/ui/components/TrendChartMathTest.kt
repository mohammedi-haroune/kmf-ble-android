package com.juncehome.lifepo4ble.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendChartMathTest {
    private val points = listOf(0f, 0.25f, 0.5f, 1f).map { TrendChartPoint(it, 1.0) }

    @Test
    fun nearestIndexPicksClosestPoint() {
        assertEquals(0, TrendChartMath.nearestIndex(points, 0.05f))
        assertEquals(1, TrendChartMath.nearestIndex(points, 0.3f))
        assertEquals(2, TrendChartMath.nearestIndex(points, 0.6f))
        assertEquals(3, TrendChartMath.nearestIndex(points, 0.9f))
        assertEquals(3, TrendChartMath.nearestIndex(points, 5f))
        assertEquals(0, TrendChartMath.nearestIndex(points, -1f))
    }

    @Test
    fun nearestIndexOfEmptyIsNull() {
        assertNull(TrendChartMath.nearestIndex(emptyList(), 0.5f))
    }

    @Test
    fun yFractionMapsTopToBottomAndClamps() {
        assertEquals(0f, TrendChartMath.yFraction(10.0, 0.0, 10.0), 1e-6f)
        assertEquals(1f, TrendChartMath.yFraction(0.0, 0.0, 10.0), 1e-6f)
        assertEquals(0.5f, TrendChartMath.yFraction(0.0, -5.0, 5.0), 1e-6f)
        assertEquals(0f, TrendChartMath.yFraction(99.0, 0.0, 10.0), 1e-6f)
        assertEquals(0.5f, TrendChartMath.yFraction(3.0, 4.0, 4.0), 1e-6f)
    }
}
