package com.gijimemo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class],
    version = 4,
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

        /** v3 → v4: 文字起こし時間 + 呼び出しモードを追加 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sessions ADD COLUMN transcribeDurationMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sessions ADD COLUMN llmCallMode TEXT DEFAULT NULL")
            }
        }
    }
}