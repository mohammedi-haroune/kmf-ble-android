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
}
