package com.gijimemo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GijiMemoDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}