package com.juncehome.lifepo4ble.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        KmfFrameEventEntity::class,
        KmfBatterySampleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KmfDatabase : RoomDatabase() {
    abstract fun kmfFrameEventDao(): KmfFrameEventDao

    abstract fun kmfBatterySampleDao(): KmfBatterySampleDao
}
