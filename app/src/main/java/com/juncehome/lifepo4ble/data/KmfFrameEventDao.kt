package com.juncehome.lifepo4ble.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KmfFrameEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: KmfFrameEventEntity)

    @Query(
        """
        SELECT *
        FROM kmf_frame_event
        WHERE deviceAddress = :deviceAddress
        ORDER BY timestampMs DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeRecent(deviceAddress: String, limit: Int): Flow<List<KmfFrameEventEntity>>
}
