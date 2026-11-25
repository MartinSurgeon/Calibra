package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val pointsJson: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val pinned: Boolean = false
)
