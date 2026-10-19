package com.juncehome.lifepo4ble.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendChartNiceTicksTest {
    @Test
    fun stepsAreOneTwoTwoPointFiveOrFiveTimesPowerOfTen() {
        assertEquals(50.0, TrendChartMath.niceYTickStep(-86.0, 86.0), 1e-9)
        assertEquals(25.0, TrendChartMath.niceYTickStep(0.0, 100.0), 1e-9)
        assertEquals(0.1, TrendChartMath.niceYTickStep(12.9, 13.3), 1e-9)
        assertEquals(0.0, TrendChartMath.niceYTickStep(1.0, 1.0), 0.0)
        assertEquals(2.5, TrendChartMath.niceYTickStep(160.0, 170.0), 1e-9)
    }

    @Test
    fun voltageTicksCarryDecimalsFromTheStep() {
        val ticks = TrendChartMath.niceYTicks(12.93, 13.57)
        assertEquals(listOf("13.0", "13.2", "13.4"), ticks.map { it.label })
        assertEquals(listOf("0.050", "0.075", "0.100"), TrendChartMath.niceYTicks(0.03, 0.12).map { it.label })
        assertEquals(2, TrendChartMath.decimalsForStep(0.25))
        assertEquals(1, TrendChartMath.decimalsForStep(2.5))
        assertEquals(0, TrendChartMath.decimalsForStep(50.0))
    }

    @Test
    fun signedRangeLabelsUseMagnitudes() {
        val ticks = TrendChartMath.niceYTicks(-86.0, 86.0)
        assertEquals(listOf(-50.0, 0.0, 50.0), ticks.map { it.value })
        assertEquals(listOf("50", "0", "50"), ticks.map { it.label })
    }

    @Test
    fun aboutFourIntervalsAtMost() {
        listOf(0.0 to 100.0, 164.0 to 200.0, -1.0 to 1.0, 0.0 to 7.3, 12.0 to 14.2).forEach { (lo, hi) ->
            val ticks = TrendChartMath.niceYTicks(lo, hi)
            assertTrue("$lo..$hi -> ${ticks.size}", ticks.size in 2..5)
        }
        assertTrue(TrendChartMath.niceYTicks(5.0, 5.0).isEmpty())
    }
}
