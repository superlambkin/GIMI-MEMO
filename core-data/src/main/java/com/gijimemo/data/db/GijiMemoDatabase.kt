package com.gijimemo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class],
    version = 3,
    exportSchema = false
)
abstract class GijiMemoDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sessions ADD COLUMN rawTranscript TEXT DEFAULT NULL")
            }
        }
    }
}