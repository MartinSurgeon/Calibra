package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.MeasurementDatabase
import com.example.data.MeasurementEntity
import com.example.domain.MeasurementSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrading users must keep their saved measurements: builds a schema-v1 database exactly as
 * Room created it, then opens it with the v2 schema + MIGRATION_1_2 (Room validates the
 * migrated table against the entity and throws if they diverge).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeasurementDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_keepsRowsAndDefaultsNewColumns() {
        val path = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `measurements` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, `shapeMode` TEXT NOT NULL, `primaryValue` REAL NOT NULL, " +
                    "`primaryUnit` TEXT NOT NULL, `secondarySummary` TEXT NOT NULL, `notes` TEXT NOT NULL, " +
                    "`calibrationRef` TEXT NOT NULL, `pointsJson` TEXT NOT NULL, " +
                    "`createdAtMillis` INTEGER NOT NULL, `pinned` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO measurements (title, category, shapeMode, primaryValue, primaryUnit, " +
                    "secondarySummary, notes, calibrationRef, pointsJson, createdAtMillis, pinned) " +
                    "VALUES ('Kitchen wall', 'Area', 'RECTANGLE', 4.2, 'm²', 'W: 2m', '', 'Standard Door', '', 1700000000000, 1)"
            )
            db.version = 1
        }

        val room = Room.databaseBuilder(context, MeasurementDatabase::class.java, dbName)
            .addMigrations(MeasurementDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.measurementDao()
            val migrated = runBlocking { dao.observeAll().first() }
            assertEquals(1, migrated.size)
            val legacy = migrated.single()
            assertEquals("Kitchen wall", legacy.title)
            assertEquals(4.2, legacy.primaryValue, 1e-9)
            assertEquals(true, legacy.pinned)
            assertEquals(MeasurementSource.CALIBRATED.name, legacy.source)
            assertEquals("", legacy.worldPointsJson)
            assertEquals("", legacy.photoPath)

            runBlocking {
                dao.insert(
                    MeasurementEntity(
                        title = "Desk",
                        category = "Length",
                        shapeMode = "LINE",
                        primaryValue = 1.2,
                        primaryUnit = "m",
                        secondarySummary = "",
                        source = MeasurementSource.ARCORE.name,
                        worldPointsJson = "0.0000,0.0000,0.0000;1.2000,0.0000,0.0000",
                        photoPath = "/data/snapshots/desk.jpg"
                    )
                )
            }
            val all = runBlocking { dao.observeAll().first() }
            val ar = all.single { it.title == "Desk" }
            assertEquals(MeasurementSource.ARCORE.name, ar.source)
            assertEquals("/data/snapshots/desk.jpg", ar.photoPath)
        } finally {
            room.close()
        }
    }
}
