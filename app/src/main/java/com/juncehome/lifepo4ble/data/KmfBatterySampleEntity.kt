package com.juncehome.lifepo4ble.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kmf_battery_sample",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["deviceAddress", "timestampMs"]),
    ],
)
data class KmfBatterySampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    val deviceAddress: String,
    val deviceName: String?,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val charging: Boolean,
    val minutesRemaining: Int,
    val remainingAh: Double,
    val capacityAh: Double,
    val socPercent: Double,
    val chargeKwh: Double,
    val dischargeKwh: Double,
    val hasAFrame: Boolean,
    val hasBFrame: Boolean,
    val hasCFrame: Boolean,
    val rawAFieldsCsv: String?,
    val rawBFieldsCsv: String?,
    val rawCFieldsCsv: String?,
    val connectionState: String,
    val serviceUuid: String?,
    val notifyUuid: String?,
    val writeUuid: String?,
)
