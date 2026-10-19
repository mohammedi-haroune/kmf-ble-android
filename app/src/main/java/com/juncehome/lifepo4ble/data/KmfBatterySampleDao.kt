package com.juncehome.lifepo4ble.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KmfBatterySampleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: KmfBatterySampleEntity)

    @Query(
        """
        SELECT *
        FROM kmf_battery_sample
        WHERE deviceAddress = :deviceAddress
        ORDER BY timestampMs DESC, id DESC
        LIMIT 1
        """
    )
    fun observeLatest(deviceAddress: String): Flow<KmfBatterySampleEntity?>

    @Query(
        """
        SELECT *
        FROM kmf_battery_sample
        WHERE deviceAddress = :deviceAddress
        ORDER BY timestampMs DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeRecent(deviceAddress: String, limit: Int): Flow<List<KmfBatterySampleEntity>>

    @Query(
        """
        SELECT *
        FROM kmf_battery_sample
        WHERE id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun readAfterId(afterId: Long, limit: Int): List<KmfBatterySampleEntity>

    @Query("DELETE FROM kmf_battery_sample")
    suspend fun deleteAll()

    /** Emits on every insert or delete; used by Trends as a cheap change token (rowid lookup). */
    @Query("SELECT MAX(id) FROM kmf_battery_sample")
    fun observeLatestId(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM kmf_battery_sample")
    suspend fun count(): Long

    /** Uses the timestampMs index. */
    @Query("SELECT MIN(timestampMs) FROM kmf_battery_sample")
    suspend fun earliestTimestampMs(): Long?

    /** Raw samples in [fromMs, toMs], oldest first, bounded by [limit]. */
    @Query(
        """
        SELECT *
        FROM kmf_battery_sample
        WHERE timestampMs >= :fromMs AND timestampMs <= :toMs
        ORDER BY timestampMs ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun readBetween(fromMs: Long, toMs: Long, limit: Int): List<KmfBatterySampleEntity>

    /** Newest sample in [fromMs, toMs] (all meters). */
    @Query(
        """
        SELECT *
        FROM kmf_battery_sample
        WHERE timestampMs >= :fromMs AND timestampMs <= :toMs
        ORDER BY timestampMs DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun readLatestBetween(fromMs: Long, toMs: Long): KmfBatterySampleEntity?

    /**
     * Chart downsampling in SQL: epoch-aligned buckets of [bucketMs] with AVG/MIN/MAX of the
     * instantaneous metrics. Returns at most (toMs - fromMs) / bucketMs + 1 rows.
     */
    @Query(
        """
        SELECT timestampMs / :bucketMs AS bucket,
            COUNT(*) AS sampleCount,
            MIN(timestampMs) AS firstMs,
            MAX(timestampMs) AS lastMs,
            AVG(powerW) AS avgPowerW, MIN(powerW) AS minPowerW, MAX(powerW) AS maxPowerW,
            AVG(socPercent) AS avgSocPercent, MIN(socPercent) AS minSocPercent, MAX(socPercent) AS maxSocPercent,
            AVG(voltageV) AS avgVoltageV, MIN(voltageV) AS minVoltageV, MAX(voltageV) AS maxVoltageV,
            AVG(currentA) AS avgCurrentA, MIN(currentA) AS minCurrentA, MAX(currentA) AS maxCurrentA,
            AVG(remainingAh) AS avgRemainingAh, MIN(remainingAh) AS minRemainingAh, MAX(remainingAh) AS maxRemainingAh
        FROM kmf_battery_sample
        WHERE timestampMs >= :fromMs AND timestampMs <= :toMs
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun readBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfSampleBucketRow>

    /**
     * Cumulative meter totals at the FIRST sample of each epoch-aligned bucket. Relies on SQLite's
     * documented bare-column rule: with a single MIN() aggregate, bare columns come from that row.
     * Rows without cumulative totals (no C frame yet, both 0) are skipped.
     */
    @Query(
        """
        SELECT timestampMs / :bucketMs AS bucket, MIN(timestampMs) AS edgeMs, chargeKwh, dischargeKwh
        FROM kmf_battery_sample
        WHERE timestampMs >= :fromMs AND timestampMs <= :toMs AND (chargeKwh > 0 OR dischargeKwh > 0)
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun readFirstCounters(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterEdgeRow>

    /**
     * Cumulative meter totals at the LAST sample of each bucket (single MAX() bare-column rule).
     * Rows without cumulative totals (no C frame yet, both 0) are skipped.
     */
    @Query(
        """
        SELECT timestampMs / :bucketMs AS bucket, MAX(timestampMs) AS edgeMs, chargeKwh, dischargeKwh
        FROM kmf_battery_sample
        WHERE timestampMs >= :fromMs AND timestampMs <= :toMs AND (chargeKwh > 0 OR dischargeKwh > 0)
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun readLastCounters(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterEdgeRow>
}

/** One SQL-aggregated chart bucket (see [KmfBatterySampleDao.readBuckets]). */
data class KmfSampleBucketRow(
    val bucket: Long,
    val sampleCount: Int,
    val firstMs: Long,
    val lastMs: Long,
    val avgPowerW: Double,
    val minPowerW: Double,
    val maxPowerW: Double,
    val avgSocPercent: Double,
    val minSocPercent: Double,
    val maxSocPercent: Double,
    val avgVoltageV: Double,
    val minVoltageV: Double,
    val maxVoltageV: Double,
    val avgCurrentA: Double,
    val minCurrentA: Double,
    val maxCurrentA: Double,
    val avgRemainingAh: Double = 0.0,
    val minRemainingAh: Double = 0.0,
    val maxRemainingAh: Double = 0.0,
)

/** Cumulative counters at one edge (first or last sample) of a bucket. */
data class KmfCounterEdgeRow(
    val bucket: Long,
    val edgeMs: Long,
    val chargeKwh: Double,
    val dischargeKwh: Double,
)
