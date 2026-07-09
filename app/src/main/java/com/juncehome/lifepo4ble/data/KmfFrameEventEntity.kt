package com.juncehome.lifepo4ble.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kmf_frame_event",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["deviceAddress", "timestampMs"]),
    ],
)
data class KmfFrameEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    val deviceAddress: String,
    val deviceName: String?,
    val direction: String,
    val frameType: String,
    val rawHex: String,
    val rawAscii: String,
    val parsedFieldsCsv: String?,
    val serviceUuid: String?,
    val notifyUuid: String?,
    val writeUuid: String?,
    val writeSuccess: Boolean?,
    val error: String?,
)
