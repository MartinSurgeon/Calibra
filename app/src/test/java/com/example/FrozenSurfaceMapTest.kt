package com.example

import com.example.ar.FrozenSurfaceMap
import com.example.domain.Mat4
import com.example.domain.NormPoint
import com.example.domain.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * A camera 1.5 m above a floor looks horizontally down -Z (as ARCore's frozen view would):
 * shapes drawn on the still image must land on the floor at their true world positions.
 */
class FrozenSurfaceMapTest {

    private val width = 1080
    private val height = 1920
    private val cameraHeight = 1.5

    private val projection: FloatArray = run {
        val near = 0.05f
        val far = 100f
        val f = (1.0 / tan(Math.toRadians(30.0))).toFloat() // 60° vertical field of view
        val aspect = width.toFloat() / height
        FloatArray(16).apply {
            this[0] = f / aspect
            this[5] = f
            this[10] = (far + near) / (near - far)
            this[11] = -1f
            this[14] = 2f * far * near / (near - far)
        }
    }

    private val view: FloatArray = FloatArray(16).apply {
        this[0] = 1f
        this[5] = 1f
        this[10] = 1f
        this[15] = 1f
        this[13] = -cameraHeight.toFloat() // camera at (0, 1.5, 0)
    }

    private val viewProjection = Mat4.multiply(Mat4.of(projection), Mat4.of(view))

    /** World point -> view pixel, with ArProjector's conventions. */
    private fun project(p: Vec3): Pair<Float, Float> {
        val ndc = Mat4.transformPoint(viewProjection, p.x, p.y, p.z)!!
        return ((ndc.x + 1.0) / 2.0 * width).toFloat() to ((1.0 - ndc.y) / 2.0 * height).toFloat()
    }

    /** Floor samples like the controller's hit-test grid: every 25 cm, kept if on screen. */
    private fun floorMap(): FrozenSurfaceMap {
        val samples = ArrayList<FrozenSurfaceMap.Sample>()
        var z = -2.5
        while (z >= -12.0) {
            var x = -2.0
            while (x <= 2.0) {
                val p = Vec3(x, 0.0, z)
                val (sx, sy) = project(p)
                if (sx in 0f..width.toFloat() && sy in 0f..height.toFloat()) {
                    samples.add(FrozenSurfaceMap.Sample(sx, sy, p, Vec3.UP))
                }
                x += 0.25
            }
            z -= 0.25
        }
        return FrozenSurfaceMap(width, height, view, projection, samples, maxGapPx = 120f)
    }

    private fun normalized(p: Vec3): NormPoint {
        val (sx, sy) = project(p)
        return NormPoint(sx / width, sy / height)
    }

    @Test
    fun ray_throughCenterLooksForward() {
        val (origin, dir) = floorMap().ray(width / 2f, height / 2f)!!
        assertEquals(0.0, dir.x, 1e-6)
        assertEquals(0.0, dir.y, 1e-6)
        assertEquals(-1.0, dir.z, 1e-6)
        assertEquals(cameraHeight, origin.y, 1e-6)
    }

    @Test
    fun worldPoint_landsOnTheFloorAtTrueScale() {
        val map = floorMap()
        assertTrue(map.hasSurfaces)
        val a = Vec3(-0.4, 0.0, -3.5)
        val b = Vec3(0.6, 0.0, -4.5)
        val wa = map.worldPoint(normalized(a))
        val wb = map.worldPoint(normalized(b))
        assertNotNull(wa)
        assertNotNull(wb)
        assertEquals(0.0, wa!!.distanceTo(a), 1e-3)
        assertEquals(0.0, wb!!.distanceTo(b), 1e-3)
        assertEquals(a.distanceTo(b), wa.distanceTo(wb), 1e-3)
    }

    @Test
    fun worldPoint_isNullFarFromAnySurfaceSample() {
        // The top of the frame shows the space above the horizon: no floor there.
        assertNull(floorMap().worldPoint(NormPoint(0.5f, 0.05f)))
    }

    @Test
    fun emptyMap_measuresNothing() {
        val empty = FrozenSurfaceMap(width, height, view, projection, emptyList(), maxGapPx = 120f)
        assertTrue(!empty.hasSurfaces)
        assertNull(empty.worldPoint(NormPoint(0.5f, 0.8f)))
    }
}
