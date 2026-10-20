package com.juncehome.lifepo4ble.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendChartYTicksTest {
    @Test
    fun chargingLikeRangeUsesSmallestNiceStepWithinFourIntervals() {
        // Span 1120 W: 25/50/100/200 W would give more than 4 intervals, so 500 W is the step.
        assertEquals(500.0, TrendChartMath.yTickStep(-120.0, 1000.0), 1e-9)
        val ticks = TrendChartMath.yTicks(-120.0, 1000.0)
        assertEquals(listOf(0.0, 500.0, 1000.0), ticks.map { it.value })
        assertEquals(listOf("0.00", "500.00", "1000.00"), ticks.map { it.label })
    }

    @Test
    fun symmetricRangeLabelsBothSidesIdentically() {
        val ticks = TrendChartMath.yTicks(-600.0, 600.0)
        assertEquals(listOf(-500.0, 0.0, 500.0), ticks.map { it.value })
        assertEquals(listOf("500.00", "0.00", "500.00"), ticks.map { it.label })
    }

    @Test
    fun smallRangeUsesFineStep() {
        // Span 300 W: 50 W would be six intervals, 100 W gives three.
        assertEquals(100.0, TrendChartMath.yTickStep(-150.0, 150.0), 1e-9)
        assertEquals(listOf("100.00", "0.00", "100.00"), TrendChartMath.yTicks(-150.0, 150.0).map { it.label })
        assertEquals(20.0, TrendChartMath.yTickStep(-40.0, 40.0), 1e-9)
    }

    @Test
    fun oneWattScaleShowsBothSidesOfZero() {
        // NowScreenModel floors the scale at 1 W, so a near-idle battery still gets -1, 0, +1.
        val ticks = TrendChartMath.yTicks(-1.0, 1.0)
        assertEquals(listOf(-1.0, 0.0, 1.0), ticks.map { it.value })
        assertEquals(listOf("1.00", "0.00", "1.00"), ticks.map { it.label })
        assertEquals(listOf(-1.0, 0.0, 1.0), TrendChartMath.yTicks(-1.4, 1.4).map { it.value })
    }

    @Test
    fun labelsNeverCarrySign() {
        val ranges = listOf(-600.0 to 600.0, -120.0 to 1000.0, -150.0 to 150.0, -40.0 to 40.0, -1500.0 to 1500.0)
        ranges.forEach { (min, max) ->
            TrendChartMath.yTicks(min, max).forEach { tick ->
                assertTrue("label '${tick.label}' has a sign", tick.label.none { it == '-' || it == '+' || it == '−' })
                assertTrue("label '${tick.label}' is not a plain number", tick.label.matches(Regex("""\d+\.\d{2}""")))
            }
        }
    }

    @Test
    fun zeroIsIncludedWhenRangeContainsIt() {
        listOf(-600.0 to 600.0, -120.0 to 1000.0, -150.0 to 150.0).forEach { (min, max) ->
            val ticks = TrendChartMath.yTicks(min, max)
            assertTrue("zero missing for $min..$max", ticks.any { it.value == 0.0 && it.label == "0.00" })
        }
    }

    @Test
    fun emptyOrInvalidRangeHasNoTicks() {
        assertTrue(TrendChartMath.yTicks(0.0, 0.0).isEmpty())
        assertTrue(TrendChartMath.yTicks(10.0, -10.0).isEmpty())
        assertTrue(TrendChartMath.yTicks(Double.NaN, 10.0).isEmpty())
    }
}
