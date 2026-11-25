package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.MeasurementSource

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val category: String,
    val shapeMode: String,
    val primaryValue: Double,
    val primaryUnit: String,
    val secondarySummary: String,
    val notes: String = "",
    val calibrationRef: String = "Default",
    /** Normalized 0..1 blueprint points used for thumbnails and the PDF report. */
    val pointsJson: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /** [MeasurementSource] name: how the geometry was captured (added in schema v2). */
    val source: String = MeasurementSource.CALIBRATED.name,
    /** World-space points in meters ("x,y,z;…") for ARCore measurements (added in schema v2). */
    val worldPointsJson: String = "",
    /** Absolute path of the captured photo, or blank (added in schema v2). */
    val photoPath: String = ""
)

val MeasurementEntity.measurementSource: MeasurementSource
    get() = MeasurementSource.fromName(source)
