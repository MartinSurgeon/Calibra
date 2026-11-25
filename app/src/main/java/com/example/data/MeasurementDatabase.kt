package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeasurementEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MeasurementDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val DATABASE_NAME = "calibra_measurements.db"

        /** v2: measurement provenance, ARCore world points and attached photos. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN source TEXT NOT NULL DEFAULT 'CALIBRATED'")
                db.execSQL("ALTER TABLE measurements ADD COLUMN worldPointsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE measurements ADD COLUMN photoPath TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: MeasurementDatabase? = null

        fun getInstance(context: Context): MeasurementDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeasurementDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
