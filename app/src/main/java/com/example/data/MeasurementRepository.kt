package com.example.data

import kotlinx.coroutines.flow.Flow

class MeasurementRepository(private val dao: MeasurementDao) {

    val allMeasurements: Flow<List<MeasurementEntity>> = dao.observeAll()

    suspend fun insert(measurement: MeasurementEntity): Long = dao.insert(measurement)

    suspend fun insertAll(measurements: List<MeasurementEntity>) = dao.insertAll(measurements)

    suspend fun update(measurement: MeasurementEntity) = dao.update(measurement)

    suspend fun togglePin(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
