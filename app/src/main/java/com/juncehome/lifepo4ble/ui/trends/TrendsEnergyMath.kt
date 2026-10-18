package com.juncehome.lifepo4ble.ui.trends

import com.juncehome.lifepo4ble.data.KmfCounterBucket
import kotlin.math.max

/** Energy moved in one fine (15 min) bucket, derived from the cumulative meter totals. */
data class FineEnergy(
    val startMs: Long,
    val inKwh: Double,
    val outKwh: Double,
)

/** Energy summed into one bar slot (hour or day). */
data class EnergySlot(
    val startMs: Long,
    val inKwh: Double,
    val outKwh: Double,
)

/**
 * Pure energy derivation for the Trends Energy card.
 *
 * The meter reports cumulative charge/discharge kWh totals. Energy for a period is the sum of
 * positive deltas between consecutive readings. SQL returns the first and last totals of each
 * 15 min bucket; the delta of a bucket is (last - first) inside it plus (first - previous last)
 * across the boundary, so energy that arrives across a collection gap lands in the bucket where
 * readings resume. Deltas are never negative: a drop (meter counter reset or a different meter)
 * contributes 0, and an implausibly large jump (more than [MAX_PLAUSIBLE_KW] sustained over the
 * elapsed time) is treated as a counter discontinuity and also contributes 0.
 */
object TrendsEnergyMath {
    /** Fine bucket size; every UTC offset is a multiple of 15 min, so buckets align with local hours and days. */
    const val FINE_BUCKET_MS: Long = 15 * 60_000L

    /** Upper bound for a believable average charge/discharge rate. */
    const val MAX_PLAUSIBLE_KW: Double = 20.0

    /** Allows for counter resolution (0.01 kWh) on short spans. */
    private const val RESOLUTION_ALLOWANCE_KWH = 0.05
    private const val MS_PER_HOUR = 3_600_000.0

    /** Positive delta from [from] to [to] over [elapsedMs], or 0 for resets, NaN and implausible jumps. */
    fun counterDelta(from: Double, to: Double, elapsedMs: Long): Double {
        val delta = to - from
        if (delta.isNaN() || delta <= 0.0) {
            return 0.0
        }
        val hours = max(elapsedMs, FINE_BUCKET_MS) / MS_PER_HOUR
        val limit = MAX_PLAUSIBLE_KW * hours + RESOLUTION_ALLOWANCE_KWH
        return if (delta > limit) 0.0 else delta
    }

    fun fineEnergy(buckets: List<KmfCounterBucket>, bucketMs: Long = FINE_BUCKET_MS): List<FineEnergy> {
        var previous: KmfCounterBucket? = null
        return buckets.sortedBy { it.bucket }.map { bucket ->
            var inKwh = counterDelta(bucket.firstChargeKwh, bucket.lastChargeKwh, bucket.lastMs - bucket.firstMs)
            var outKwh = counterDelta(bucket.firstDischargeKwh, bucket.lastDischargeKwh, bucket.lastMs - bucket.firstMs)
            previous?.let { before ->
                val gapMs = bucket.firstMs - before.lastMs
                inKwh += counterDelta(before.lastChargeKwh, bucket.firstChargeKwh, gapMs)
                outKwh += counterDelta(before.lastDischargeKwh, bucket.firstDischargeKwh, gapMs)
            }
            previous = bucket
            FineEnergy(startMs = bucket.bucket * bucketMs, inKwh = inKwh, outKwh = outKwh)
        }
    }

    /**
     * Sums [fine] into slots; slot i covers [slotStartsMs[i], slotStartsMs[i + 1]) and the last
     * slot ends at [endMs]. Fine buckets outside the slots are ignored.
     */
    fun slots(fine: List<FineEnergy>, slotStartsMs: List<Long>, endMs: Long): List<EnergySlot> {
        if (slotStartsMs.isEmpty()) {
            return emptyList()
        }
        val inTotals = DoubleArray(slotStartsMs.size)
        val outTotals = DoubleArray(slotStartsMs.size)
        fine.forEach { energy ->
            if (energy.startMs < slotStartsMs.first() || energy.startMs >= endMs) {
                return@forEach
            }
            val found = slotStartsMs.binarySearch(energy.startMs)
            val index = if (found >= 0) found else -found - 2
            if (index in slotStartsMs.indices) {
                inTotals[index] += energy.inKwh
                outTotals[index] += energy.outKwh
            }
        }
        return slotStartsMs.mapIndexed { index, start -> EnergySlot(start, inTotals[index], outTotals[index]) }
    }

    /** True when at least one fine bucket falls inside the slots (distinguishes "0.00" from "no data"). */
    fun hasData(fine: List<FineEnergy>, slotStartsMs: List<Long>, endMs: Long): Boolean =
        slotStartsMs.isNotEmpty() && fine.any { it.startMs >= slotStartsMs.first() && it.startMs < endMs }
}
