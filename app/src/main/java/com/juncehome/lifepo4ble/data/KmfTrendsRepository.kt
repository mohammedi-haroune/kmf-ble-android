package com.juncehome.lifepo4ble.data

import kotlinx.coroutines.flow.Flow

/** Saved-history totals for the Trends "Saved history" card. */
data class KmfHistorySummary(
    val count: Long,
    val earliestMs: Long?,
    /** On-disk size of the database plus its WAL/SHM files; 0 when unknown. */
    val dbSizeBytes: Long = 0L,
)

/**
 * Cumulative meter totals at the first and last sample of one fine energy bucket
 * (`bucket * bucketMs` is the bucket start in epoch ms).
 */
data class KmfCounterBucket(
    val bucket: Long,
    val firstMs: Long,
    val firstChargeKwh: Double,
    val firstDischargeKwh: Double,
    val lastMs: Long,
    val lastChargeKwh: Double,
    val lastDischargeKwh: Double,
)

/** Read side of saved battery history used by the Trends tab. All queries span every meter. */
interface KmfTrendsStore {
    /** Emits whenever samples are inserted or deleted. */
    fun observeChanges(): Flow<Long?>

    suspend fun readBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfSampleBucketRow>

    suspend fun readLatest(fromMs: Long, toMs: Long): KmfBatterySampleEntity?

    suspend fun readCounterBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterBucket>

    suspend fun readSummary(): KmfHistorySummary
}

class KmfTrendsRepository(
    private val dao: KmfBatterySampleDao,
    private val dbSizeBytes: () -> Long = { 0L },
) : KmfTrendsStore {
    override fun observeChanges(): Flow<Long?> = dao.observeLatestId()

    override suspend fun readBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfSampleBucketRow> =
        dao.readBuckets(fromMs, toMs, bucketMs)

    override suspend fun readLatest(fromMs: Long, toMs: Long): KmfBatterySampleEntity? =
        dao.readLatestBetween(fromMs, toMs)

    override suspend fun readCounterBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterBucket> =
        mergeCounterEdges(
            first = dao.readFirstCounters(fromMs, toMs, bucketMs),
            last = dao.readLastCounters(fromMs, toMs, bucketMs),
        )

    override suspend fun readSummary(): KmfHistorySummary =
        KmfHistorySummary(
            count = dao.count(),
            earliestMs = dao.earliestTimestampMs(),
            dbSizeBytes = dbSizeBytes(),
        )

    companion object {
        /** Joins first/last edge rows by bucket; buckets missing on either side are dropped. */
        fun mergeCounterEdges(first: List<KmfCounterEdgeRow>, last: List<KmfCounterEdgeRow>): List<KmfCounterBucket> {
            val lastByBucket = last.associateBy { it.bucket }
            return first.mapNotNull { start ->
                val end = lastByBucket[start.bucket] ?: return@mapNotNull null
                KmfCounterBucket(
                    bucket = start.bucket,
                    firstMs = start.edgeMs,
                    firstChargeKwh = start.chargeKwh,
                    firstDischargeKwh = start.dischargeKwh,
                    lastMs = end.edgeMs,
                    lastChargeKwh = end.chargeKwh,
                    lastDischargeKwh = end.dischargeKwh,
                )
            }.sortedBy { it.bucket }
        }
    }
}
