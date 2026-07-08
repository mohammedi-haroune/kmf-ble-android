package com.juncehome.lifepo4ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class KmfReadingMergerTest {
    @Test
    fun cFrameDoesNotEraseLatestAReading() {
        val afterA = KmfReadingMerger.apply(
            KmfReading(),
            KmfFrame.A(
                voltageV = 12.34,
                currentA = 2.5,
                powerW = 30.85,
                charging = true,
                minutesRemaining = 120,
                remainingAh = 2.4,
                capacityAh = 100.0,
                socPercent = 2.4,
                status = "Charging",
            ),
        )

        val afterC = KmfReadingMerger.apply(
            afterA,
            KmfFrame.C(chargeKwh = 0.012, dischargeKwh = 0.034),
        )

        assertEquals(12.34, afterC.voltageV, 0.001)
        assertEquals(0.012, afterC.chargeKwh, 0.001)
        assertEquals(0.034, afterC.dischargeKwh, 0.001)
    }
}
