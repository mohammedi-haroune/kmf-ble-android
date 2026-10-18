package com.juncehome.lifepo4ble.ui.trends

import com.juncehome.lifepo4ble.data.KmfCounterBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsEnergyMathTest {
    private val fine = TrendsEnergyMath.FINE_BUCKET_MS

    private fun bucket(index: Long, charge: Pair<Double, Double>, discharge: Pair<Double, Double>, spanMs: Long = fine - 1) =
        KmfCounterBucket(
            bucket = index,
            firstMs = index * fine,
            firstChargeKwh = charge.first,
            firstDischargeKwh = discharge.first,
            lastMs = index * fine + spanMs,
            lastChargeKwh = charge.second,
            lastDischargeKwh = discharge.second,
        )

    @Test
    fun deltaIsNeverNegative() {
        assertEquals(0.0, TrendsEnergyMath.counterDelta(5.0, 4.0, fine), 0.0)
        assertEquals(0.0, TrendsEnergyMath.counterDelta(5.0, Double.NaN, fine), 0.0)
        assertEquals(0.3, TrendsEnergyMath.counterDelta(5.0, 5.3, fine), 1e-9)
    }

    @Test
    fun counterResetContributesZero() {
        val energy = TrendsEnergyMath.fineEnergy(
            listOf(
                bucket(1, 100.0 to 100.2, 50.0 to 50.1),
                bucket(2, 0.1 to 0.3, 0.0 to 0.1),
            ),
        )
        assertEquals(0.2, energy[1].inKwh, 1e-9)
        assertEquals(0.1, energy[1].outKwh, 1e-9)
    }

    @Test
    fun implausibleJumpContributesZero() {
        assertEquals(0.0, TrendsEnergyMath.counterDelta(1.0, 500.0, fine), 0.0)
    }

    @Test
    fun crossBoundaryDeltaLandsInLaterBucket() {
        val energy = TrendsEnergyMath.fineEnergy(
            listOf(
                bucket(1, 10.00 to 10.10, 1.0 to 1.0),
                bucket(2, 10.30 to 10.40, 1.0 to 1.0),
            ),
        )
        assertEquals(0.10, energy[0].inKwh, 1e-9)
        assertEquals(0.30, energy[1].inKwh, 1e-9)
    }

    @Test
    fun fineEnergy_bucketsFromNonZeroCountersKeepCrossBoundaryDelta() {
        val energy = TrendsEnergyMath.fineEnergy(
            listOf(
                bucket(0, 114.000 to 114.010, 113.000 to 113.020, spanMs = 600_000L),
                bucket(1, 114.020 to 114.030, 113.050 to 113.060, spanMs = 600_000L),
            ),
        )
        val totalIn = energy.sumOf { it.inKwh }
        val totalOut = energy.sumOf { it.outKwh }
        assertEquals(0.06, totalOut, 1e-9)
        assertEquals(0.03, totalIn, 1e-9)
    }

    @Test
    fun slotsSumFineBucketsAndIgnoreOutsiders() {
        val hour = 3_600_000L
        val fineEnergy = listOf(
            FineEnergy(-fine, 9.0, 9.0),
            FineEnergy(0, 0.1, 0.0),
            FineEnergy(fine, 0.2, 0.1),
            FineEnergy(hour, 0.4, 0.0),
            FineEnergy(3 * hour, 7.0, 7.0),
        )
        val slots = TrendsEnergyMath.slots(fineEnergy, listOf(0L, hour), endMs = 2 * hour)
        assertEquals(2, slots.size)
        assertEquals(0.3, slots[0].inKwh, 1e-9)
        assertEquals(0.1, slots[0].outKwh, 1e-9)
        assertEquals(0.4, slots[1].inKwh, 1e-9)
    }

    @Test
    fun hasDataDistinguishesEmpty() {
        assertFalse(TrendsEnergyMath.hasData(emptyList(), listOf(0L), 10L * fine))
        assertTrue(TrendsEnergyMath.hasData(listOf(FineEnergy(0, 0.0, 0.0)), listOf(0L), 10L * fine))
    }
}
