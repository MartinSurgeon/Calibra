package com.example.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class LevelReading(
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val hasSensor: Boolean = false
) {
    /** Deviation from flat horizontal (0°) or upright vertical (90°) measurement plane. */
    val planeDeviationDeg: Float
        get() {
            val fromFlat = sqrt(pitchDeg * pitchDeg + rollDeg * rollDeg)
            val fromVertical = abs(abs(pitchDeg) - 90f) + abs(rollDeg) * 0.5f
            return minOf(fromFlat, fromVertical)
        }

    val isLevel: Boolean
        get() = !hasSensor || planeDeviationDeg <= 3.5f
}

class DeviceLevelSensor(private val context: Context) {

    fun observeLevel(): Flow<LevelReading> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensorManager == null || sensor == null) {
            trySend(LevelReading(0f, 0f, hasSensor = false))
            awaitClose { }
            return@callbackFlow
        }

        var smoothX = 0f
        var smoothY = 0f
        var smoothZ = 9.81f
        val alpha = 0.16f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                smoothX += alpha * (event.values[0] - smoothX)
                smoothY += alpha * (event.values[1] - smoothY)
                smoothZ += alpha * (event.values[2] - smoothZ)

                val pitchRad = atan2(smoothY.toDouble(), sqrt((smoothX * smoothX + smoothZ * smoothZ).toDouble()))
                val rollRad = atan2(smoothX.toDouble(), sqrt((smoothY * smoothY + smoothZ * smoothZ).toDouble()))

                trySend(
                    LevelReading(
                        pitchDeg = Math.toDegrees(pitchRad).toFloat(),
                        rollDeg = Math.toDegrees(rollRad).toFloat(),
                        hasSensor = true
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
