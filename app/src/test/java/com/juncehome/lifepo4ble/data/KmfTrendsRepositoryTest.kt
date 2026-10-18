package com.juncehome.lifepo4ble.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KmfTrendsRepositoryTest {
    private fun edge(bucket: Long, ms: Long, charge: Double) = KmfCounterEdgeRow(bucket, ms, charge, charge + 100)

    @Test
    fun mergeDropsUnmatchedAndSorts() {
        val first = listOf(edge(3, 30, 3.0), edge(1, 10, 1.0), edge(2, 20, 2.0))
        val last = listOf(edge(1, 15, 1.5), edge(3, 35, 3.5), edge(4, 45, 4.5))

        val merged = KmfTrendsRepository.mergeCounterEdges(first, last)

        assertEquals(listOf(1L, 3L), merged.map { it.bucket })
        assertEquals(10L, merged[0].firstMs)
        assertEquals(15L, merged[0].lastMs)
        assertEquals(1.0, merged[0].firstChargeKwh, 0.0)
        assertEquals(1.5, merged[0].lastChargeKwh, 0.0)
        assertEquals(103.5, merged[1].lastDischargeKwh, 0.0)
    }
}
