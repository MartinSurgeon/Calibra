package com.example.ar

import com.example.domain.Mat4
import com.example.domain.NormPoint
import com.example.domain.SketchScale
import com.example.domain.Vec3
import kotlin.math.abs

/**
 * World lookup for a frozen AR camera frame, so shapes drawn on the still image measure in real
 * meters after the live camera has moved on.
 *
 * When the view is frozen, [ArSessionController] hit-tests a grid of screen points against
 * ARCore's surfaces (planes, depth, oriented feature points) and stores each hit with its surface
 * normal. A screen point is then mapped by casting the frozen camera's ray through it onto the
 * local surface of the nearest sample: exact on planar surfaces, and never extrapolated further
 * than [maxGapPx] from real surface data. Pure Kotlin (no ARCore types) so it is unit-testable.
 *
 * @param viewMatrix camera view matrix at freeze time (column-major, as ARCore returns it)
 * @param projectionMatrix camera projection matrix at freeze time (column-major)
 */
class FrozenSurfaceMap(
    val width: Int,
    val height: Int,
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray,
    val samples: List<Sample>,
    private val maxGapPx: Float
) : SketchScale {

    /** A surface hit at screen pixel ([x], [y]) with its world [point] and unit [normal]. */
    class Sample(val x: Float, val y: Float, val point: Vec3, val normal: Vec3)

    private val inverseViewProjection: DoubleArray? =
        Mat4.invert(Mat4.multiply(Mat4.of(projectionMatrix), Mat4.of(viewMatrix)))

    /** True when at least part of the frozen frame can be measured. */
    val hasSurfaces: Boolean get() = samples.isNotEmpty() && inverseViewProjection != null

    /** World ray (origin on the near plane, unit direction) through view pixel ([px], [py]). */
    fun ray(px: Float, py: Float): Pair<Vec3, Vec3>? {
        val inverse = inverseViewProjection ?: return null
        if (width <= 0 || height <= 0) return null
        val ndcX = 2.0 * px / width - 1.0
        val ndcY = 1.0 - 2.0 * py / height
        val near = Mat4.transformPoint(inverse, ndcX, ndcY, -1.0) ?: return null
        val far = Mat4.transformPoint(inverse, ndcX, ndcY, 1.0) ?: return null
        val direction = (far - near).normalized()
        if (direction == Vec3.ZERO) return null
        return near to direction
    }

    override fun worldPoint(p: NormPoint): Vec3? {
        if (samples.isEmpty()) return null
        val px = p.x * width
        val py = p.y * height
        var nearest: Sample? = null
        var bestSq = maxGapPx * maxGapPx
        for (s in samples) {
            val dx = s.x - px
            val dy = s.y - py
            val dSq = dx * dx + dy * dy
            if (dSq <= bestSq) {
                bestSq = dSq
                nearest = s
            }
        }
        val sample = nearest ?: return null
        val (origin, direction) = ray(px, py) ?: return null
        val toSample = sample.point - origin
        val sampleDepth = toSample.length
        val facing = direction dot sample.normal
        if (abs(facing) > GRAZING_COS) {
            val t = (toSample dot sample.normal) / facing
            if (t > 0.0 && t < sampleDepth * MAX_DEPTH_RATIO) return origin + direction * t
        }
        // Grazing view of the local surface: keep the sample's distance along this ray instead.
        return origin + direction * sampleDepth
    }

    companion object {
        /** Below this |cos| between ray and normal the ray-plane intersection is ill-conditioned. */
        private const val GRAZING_COS = 0.08

        /** Rejects intersections implausibly far behind the sampled surface. */
        private const val MAX_DEPTH_RATIO = 3.0
    }
}
