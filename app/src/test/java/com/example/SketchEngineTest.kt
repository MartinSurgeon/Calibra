package com.example

import com.example.domain.CalibratedSketchScale
import com.example.domain.LengthDisplayUnit
import com.example.domain.NormPoint
import com.example.domain.ShapeMode
import com.example.domain.SketchEngine
import com.example.domain.SketchScale
import com.example.domain.SketchShape
import com.example.domain.SketchTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchEngineTest {

    // 2 m across the viewport width; viewport twice as tall as wide (so 0.25 of the height == 0.5 of the width).
    private val aspect = 2f
    private val scale = CalibratedSketchScale(cmPerNormWidth = 200f, aspectHeightOverWidth = aspect)
    private val eps = 1e-4

    private fun shape(tool: SketchTool, vararg pts: Pair<Float, Float>) =
        SketchShape(id = 1, tool = tool, points = pts.map { NormPoint(it.first, it.second) })

    private fun measure(s: SketchShape) = SketchEngine.measure(s, scale, LengthDisplayUnit.M, aspect)

    @Test
    fun line_measuresHorizontalAndVerticalWithCalibratedScale() {
        val horizontal = measure(shape(SketchTool.LINE, 0.1f to 0.5f, 0.6f to 0.5f))
        assertNotNull(horizontal)
        assertEquals(ShapeMode.LINE, horizontal!!.result.shapeMode)
        assertEquals(1.0, horizontal.result.primaryValue, eps)

        // 0.25 of the height = 0.5 widths = 1 m; world +Y is screen-up, so it reads as rise.
        val vertical = measure(shape(SketchTool.LINE, 0.5f to 0.1f, 0.5f to 0.35f))!!
        assertEquals(1.0, vertical.result.primaryValue, eps)
        assertEquals("1m", vertical.result.secondaryMetrics.toMap()["Rise"])
    }

    @Test
    fun box_reportsAreaAndEdgesRegardlessOfDragDirection() {
        val forward = measure(shape(SketchTool.RECTANGLE, 0.1f to 0.1f, 0.6f to 0.35f))!!
        val backward = measure(shape(SketchTool.RECTANGLE, 0.6f to 0.35f, 0.1f to 0.1f))!!
        listOf(forward, backward).forEach { m ->
            assertEquals(ShapeMode.RECTANGLE, m.result.shapeMode)
            assertEquals(1.0, m.result.primaryValue, eps) // 1 m x 1 m
            m.result.edgeLabelsCm.forEach { assertEquals(100.0, it, 1e-3) }
            assertEquals(4, m.worldPoints.size)
        }
        assertEquals(2, forward.labels.size)
        assertNotNull(forward.centerLabel)
    }

    @Test
    fun circle_diameterFromScreenRadius() {
        // Radius 0.25 widths = 0.5 m -> diameter 1 m.
        val m = measure(shape(SketchTool.CIRCLE, 0.5f to 0.25f, 0.75f to 0.25f))!!
        assertEquals(ShapeMode.CIRCLE, m.result.shapeMode)
        assertEquals(1.0, m.result.primaryValue, 1e-3)
    }

    @Test
    fun freehand_closedLoopMeasuresTracedArea() {
        val square = shape(
            SketchTool.FREEHAND,
            0.2f to 0.2f, 0.45f to 0.2f, 0.7f to 0.2f, 0.7f to 0.325f, 0.7f to 0.45f,
            0.45f to 0.45f, 0.2f to 0.45f, 0.2f to 0.3f, 0.2f to 0.21f
        )
        assertTrue(SketchEngine.isClosedLoop(square.points, aspect))
        val m = measure(square)!!
        assertEquals(ShapeMode.POLYGON, m.result.shapeMode)
        assertEquals(1.0, m.result.primaryValue, 1e-3)
        // Closed paths are saved as a polygon outline (short paths are kept whole).
        assertEquals(square.points, SketchEngine.blueprintPoints(square, aspect))
    }

    @Test
    fun freehand_openPathMeasuresTracedLength() {
        val path = shape(
            SketchTool.FREEHAND,
            0.1f to 0.1f, 0.35f to 0.1f, 0.6f to 0.1f, 0.6f to 0.225f, 0.6f to 0.35f
        )
        assertFalse(SketchEngine.isClosedLoop(path.points, aspect))
        val m = measure(path)!!
        assertEquals(ShapeMode.LINE, m.result.shapeMode)
        assertEquals("Path", m.result.primaryLabel)
        assertEquals(2.0, m.result.primaryValue, eps)
        assertEquals(2, SketchEngine.blueprintPoints(path, aspect).size)
    }

    @Test
    fun measure_isNullWhenAnyPointHasNoSurface() {
        val noSurface = SketchScale { null }
        val line = shape(SketchTool.LINE, 0.1f to 0.1f, 0.5f to 0.5f)
        assertNull(SketchEngine.measure(line, noSurface, LengthDisplayUnit.M, aspect))

        val halfKnown = SketchScale { p -> if (p.x < 0.5f) scale.worldPoint(p) else null }
        assertNull(SketchEngine.measure(line.copy(points = listOf(NormPoint(0.1f, 0.1f), NormPoint(0.9f, 0.1f))), halfKnown, LengthDisplayUnit.M, aspect))
    }

    @Test
    fun moveHandle_reshapesBoxFromAnyCorner() {
        val box = shape(SketchTool.RECTANGLE, 0.2f to 0.2f, 0.6f to 0.5f)
        val moved = SketchEngine.moveHandle(box, index = 1, to = NormPoint(0.7f, 0.1f))
        assertEquals(
            listOf(NormPoint(0.2f, 0.1f), NormPoint(0.7f, 0.1f), NormPoint(0.7f, 0.5f), NormPoint(0.2f, 0.5f)),
            SketchEngine.handles(moved)
        )
    }

    @Test
    fun moveHandle_circleCenterMovesWholeCircle() {
        val circle = shape(SketchTool.CIRCLE, 0.5f to 0.5f, 0.6f to 0.5f)
        val moved = SketchEngine.moveHandle(circle, index = 0, to = NormPoint(0.3f, 0.4f))
        assertEquals(0.3f, moved.points[0].x, 1e-6f)
        assertEquals(0.4f, moved.points[0].y, 1e-6f)
        assertEquals(0.4f, moved.points[1].x, 1e-6f)
        assertEquals(0.4f, moved.points[1].y, 1e-6f)
    }

    @Test
    fun translate_stopsAtViewportEdges() {
        val line = shape(SketchTool.LINE, 0.8f to 0.5f, 0.9f to 0.6f)
        val moved = SketchEngine.translate(line, dx = 0.5f, dy = -0.1f)
        assertEquals(0.9f, moved.points[0].x, 1e-6f)
        assertEquals(1.0f, moved.points[1].x, 1e-6f)
        assertEquals(0.4f, moved.points[0].y, 1e-6f)
    }

    @Test
    fun pick_prefersNearestOutlineThenEnclosingShape() {
        val w = 1000
        val h = 2000
        val line = SketchShape(1, SketchTool.LINE, listOf(NormPoint(0.1f, 0.1f), NormPoint(0.9f, 0.1f)))
        val box = SketchShape(2, SketchTool.RECTANGLE, listOf(NormPoint(0.2f, 0.3f), NormPoint(0.8f, 0.6f)))
        val shapes = listOf(line, box)
        assertEquals(1L, SketchEngine.pick(shapes, 500f, 210f, w, h, 30f)?.id) // 10 px from the line
        assertEquals(2L, SketchEngine.pick(shapes, 500f, 900f, w, h, 30f)?.id) // inside the box
        assertNull(SketchEngine.pick(shapes, 500f, 1600f, w, h, 30f))
    }

    @Test
    fun snapLineEnd_locksNearlyHorizontalAndVertical() {
        val start = NormPoint(0.2f, 0.5f)
        val nearlyLevel = SketchEngine.snapLineEnd(start, NormPoint(0.8f, 0.505f), 1000, 1000)
        assertEquals(0.5f, nearlyLevel.y, 1e-6f)
        val nearlyPlumb = SketchEngine.snapLineEnd(start, NormPoint(0.205f, 0.9f), 1000, 1000)
        assertEquals(0.2f, nearlyPlumb.x, 1e-6f)
        val diagonal = NormPoint(0.6f, 0.9f)
        assertEquals(diagonal, SketchEngine.snapLineEnd(start, diagonal, 1000, 1000))
    }

    @Test
    fun appendPathPoint_skipsJitterAndBoundsLength() {
        var path = listOf(NormPoint(0f, 0f))
        path = SketchEngine.appendPathPoint(path, NormPoint(0.001f, 0f), minStepPx = 5f, width = 1000, height = 1000)
        assertEquals(1, path.size)
        for (i in 1..1000) {
            path = SketchEngine.appendPathPoint(path, NormPoint(i / 1000f, 0f), minStepPx = 0.5f, width = 1000, height = 1000)
        }
        assertTrue(path.size <= SketchEngine.MAX_FREEHAND_POINTS)
        assertEquals(NormPoint(0f, 0f), path.first())
        assertEquals(NormPoint(1f, 0f), path.last())
    }
}
