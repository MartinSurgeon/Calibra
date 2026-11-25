package com.example

import com.example.domain.LengthDisplayUnit
import com.example.domain.ShapeMode
import com.example.domain.SpatialMeasurementEngine
import com.example.domain.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SpatialMeasurementEngineTest {

    private val eps = 1e-6

    @Test
    fun line_reportsLengthRunRiseAndSlope() {
        val pts = listOf(Vec3(0.0, 0.0, 0.0), Vec3(3.0, 4.0, 0.0))
        val r = SpatialMeasurementEngine.calculate(ShapeMode.LINE, pts, LengthDisplayUnit.M)
        assertEquals(5.0, r.primaryValue, eps)
        assertEquals("m", r.primaryUnitSymbol)
        val metrics = r.secondaryMetrics.toMap()
        assertEquals("3m", metrics["Run"])
        assertEquals("4m", metrics["Rise"])
        assertEquals("53.1°", metrics["Slope"])
    }

    @Test
    fun line_convertsToDisplayUnits() {
        val pts = listOf(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0254))
        val inches = SpatialMeasurementEngine.calculate(ShapeMode.LINE, pts, LengthDisplayUnit.IN)
        assertEquals(1.0, inches.primaryValue, 1e-9)
        val mm = SpatialMeasurementEngine.calculate(ShapeMode.LINE, pts, LengthDisplayUnit.MM)
        assertEquals(25.4, mm.primaryValue, 1e-9)
    }

    @Test
    fun rectangle_derivesPerpendicularFourthCornerAndArea() {
        // 2 m along X, third tap is off-axis: only its perpendicular component (1.5 m) counts.
        val a = Vec3(0.0, 0.0, 0.0)
        val b = Vec3(2.0, 0.0, 0.0)
        val c = Vec3(2.7, 0.0, 1.5)
        val corners = SpatialMeasurementEngine.rectangleCorners(a, b, c)
        assertEquals(Vec3(2.0, 0.0, 1.5), corners[2])
        assertEquals(Vec3(0.0, 0.0, 1.5), corners[3])

        val r = SpatialMeasurementEngine.calculate(ShapeMode.RECTANGLE, listOf(a, b, c), LengthDisplayUnit.M)
        assertEquals(3.0, r.primaryValue, eps)
        assertEquals("7m", r.perimeterDisplay)
        assertEquals("2.5m", r.secondaryMetrics.toMap()["Diag"])
    }

    @Test
    fun polygonArea_isOrientationIndependentIn3D() {
        // Unit square tilted 30 degrees about the X axis: area must still be 1 m².
        val t = PI / 6
        val square = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 0.0, 0.0),
            Vec3(1.0, sin(t), cos(t)),
            Vec3(0.0, sin(t), cos(t))
        )
        assertEquals(1.0, SpatialMeasurementEngine.polygonArea(square), eps)
        assertEquals(1.0, SpatialMeasurementEngine.polygonArea(square.reversed()), eps)

        val r = SpatialMeasurementEngine.calculate(ShapeMode.POLYGON, square, LengthDisplayUnit.CM)
        assertEquals(10_000.0, r.primaryValue, 1e-3)
        assertEquals("4", r.secondaryMetrics.toMap()["Sides"])
    }

    @Test
    fun angle_isMeasuredInTrue3D() {
        val r = SpatialMeasurementEngine.calculate(
            ShapeMode.ANGLE,
            listOf(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, Vec3(0.0, 0.0, 2.0)),
            LengthDisplayUnit.M
        )
        assertEquals(90.0, r.primaryValue, eps)
        assertEquals("°", r.primaryUnitSymbol)
    }

    @Test
    fun circle_diameterAreaAndCircumference() {
        val r = SpatialMeasurementEngine.calculate(
            ShapeMode.CIRCLE,
            listOf(Vec3(1.0, 0.8, 1.0), Vec3(1.5, 0.8, 1.0)),
            LengthDisplayUnit.M
        )
        assertEquals(1.0, r.primaryValue, eps)
        assertEquals("0.79m²", r.surfaceAreaDisplay)
        assertEquals("3.14m", r.perimeterDisplay)
    }

    @Test
    fun box_volumeSurfaceAndHeightFromTopPoint() {
        val pts = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(2.0, 0.0, 0.0),
            Vec3(2.0, 0.0, 3.0),
            Vec3(2.3, 1.5, 3.4) // only the vertical component above the base matters
        )
        val r = SpatialMeasurementEngine.calculate(ShapeMode.SNAP_3D, pts, LengthDisplayUnit.M)
        assertEquals(9.0, r.primaryValue, eps)
        assertEquals("m³", r.primaryUnitSymbol)
        assertEquals("27m²", r.surfaceAreaDisplay) // 2*(6 + 3 + 4.5)
        assertEquals("10m", r.perimeterDisplay)
        assertEquals("1.5m", r.secondaryMetrics.toMap()["H"])

        val corners = SpatialMeasurementEngine.boxCorners(pts)
        assertEquals(8, corners.size)
        assertEquals(1.5, corners[6].y, eps)
    }

    @Test
    fun closestAxisParameter_findsHeightOnVerticalAxis() {
        // Camera 2 m away looking slightly up at a vertical axis rising from the origin.
        val t = SpatialMeasurementEngine.closestAxisParameter(
            origin = Vec3.ZERO,
            dir = Vec3.UP,
            rayOrigin = Vec3(0.0, 1.0, 2.0),
            rayDir = Vec3(0.0, 0.5, -2.0)
        )
        assertNotNull(t)
        assertEquals(1.5, t!!, 1e-6)

        // Ray parallel to the axis has no unique closest point.
        assertNull(
            SpatialMeasurementEngine.closestAxisParameter(Vec3.ZERO, Vec3.UP, Vec3(1.0, 0.0, 0.0), Vec3.UP)
        )
    }

    @Test
    fun incompleteShapes_produceZeroResultsInsteadOfCrashing() {
        ShapeMode.entries.forEach { mode ->
            val empty = SpatialMeasurementEngine.calculate(mode, emptyList(), LengthDisplayUnit.M)
            assertEquals(0.0, empty.primaryValue, eps)
            val one = SpatialMeasurementEngine.calculate(mode, listOf(Vec3.ZERO), LengthDisplayUnit.M)
            assertEquals(0.0, one.primaryValue, eps)
        }
    }

    @Test
    fun blueprint_staysInsideUnitSquareAndKeepsBoxLayout() {
        val box = listOf(
            Vec3(0.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0), Vec3(4.0, 0.0, 3.0), Vec3(4.0, 2.5, 3.0)
        )
        val bp = SpatialMeasurementEngine.blueprint(ShapeMode.SNAP_3D, box)
        assertEquals(6, bp.size)
        bp.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
        // Front face: top edge above bottom edge in screen space (y grows downward).
        assertTrue(bp[0].y < bp[3].y)

        val square = SpatialMeasurementEngine.blueprint(
            ShapeMode.RECTANGLE,
            listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, 1.0))
        )
        assertEquals(4, square.size)
        val w = square[1].x - square[0].x
        val h = square[2].y - square[1].y
        assertEquals(kotlin.math.abs(w.toDouble()), kotlin.math.abs(h.toDouble()), 1e-4)
    }

    @Test
    fun serialization_roundTrips() {
        val pts = listOf(Vec3(1.2345, -0.5, 3.0), Vec3(0.0, 2.0, -1.25))
        val restored = SpatialMeasurementEngine.deserialize(SpatialMeasurementEngine.serialize(pts))
        assertEquals(2, restored.size)
        assertTrue(SpatialMeasurementEngine.maxDeviation(pts, restored) < 1e-4)
        assertTrue(SpatialMeasurementEngine.deserialize("garbage;1,2").isEmpty())
    }

    @Test
    fun stepHints_coverEveryModeAndStep() {
        ShapeMode.entries.forEach { mode ->
            val steps = (SpatialMeasurementEngine.requiredPoints(mode) ?: 4)
            repeat(steps) { placed ->
                assertTrue(SpatialMeasurementEngine.stepHint(mode, placed).isNotBlank())
            }
        }
        assertEquals(sqrt(2.0), Vec3(1.0, 1.0, 0.0).length, eps)
    }
}
