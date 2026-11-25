package com.example.domain

import kotlin.math.sqrt

/** Immutable 3D vector in meters (ARCore world space: +Y is up, opposite gravity). */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    val length: Double get() = sqrt(this dot this)

    fun normalized(): Vec3 {
        val len = length
        return if (len < 1e-9) ZERO else this * (1.0 / len)
    }

    fun distanceTo(o: Vec3): Double = (this - o).length

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)

        fun of(x: Float, y: Float, z: Float) = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

        fun of(xyz: FloatArray) = Vec3(xyz[0].toDouble(), xyz[1].toDouble(), xyz[2].toDouble())

        fun centroid(points: List<Vec3>): Vec3 {
            if (points.isEmpty()) return ZERO
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            points.forEach { sx += it.x; sy += it.y; sz += it.z }
            val n = points.size.toDouble()
            return Vec3(sx / n, sy / n, sz / n)
        }
    }
}
