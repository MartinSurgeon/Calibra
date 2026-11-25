package com.example.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Drawing tools of the sketch overlay. */
enum class SketchTool(val label: String, val hint: String) {
    LINE("Line", "Drag across what you want to measure"),
    RECTANGLE("Box", "Drag from one corner to the opposite corner"),
    CIRCLE("Circle", "Drag from the center out to the edge"),
    FREEHAND("Free", "Trace an edge — finish where you started to get its area")
}

/**
 * A shape drawn on the sketch overlay, in normalized viewport coordinates (0..1, y down).
 * LINE: start, end. RECTANGLE: two opposite corners. CIRCLE: center, rim. FREEHAND: the traced path.
 */
data class SketchShape(
    val id: Long,
    val tool: SketchTool,
    val points: List<NormPoint>,
    val colorIndex: Int = 0
)

/** Maps a normalized viewport point onto the measured surface, in meters; null where unknown. */
fun interface SketchScale {
    fun worldPoint(p: NormPoint): Vec3?
}

/**
 * Calibrated 2D camera: the viewport is treated as a flat plane facing the camera at the uniform
 * scale set on the Calibrate tab. World +Y is screen-up so line "Rise" reads as vertical span.
 */
class CalibratedSketchScale(cmPerNormWidth: Float, aspectHeightOverWidth: Float) : SketchScale {
    private val metersPerWidth = cmPerNormWidth / 100.0
    private val ratio = GeometryEngine.clampAspect(aspectHeightOverWidth).toDouble()

    override fun worldPoint(p: NormPoint): Vec3 =
        Vec3(p.x * metersPerWidth, -p.y * ratio * metersPerWidth, 0.0)
}

/** Dimension pill drawn along [from]→[to]; a horizontal pill at [from] when both are equal. */
data class SketchLabel(val from: NormPoint, val to: NormPoint, val text: String)

/** Large translucent value (area) drawn inside a closed shape. */
data class SketchCenterLabel(val at: NormPoint, val text: String)

data class SketchMeasurement(
    val result: ShapeMeasurementResult,
    /** Surface points (meters) behind the measurement, in the layout of [result]'s shape mode. */
    val worldPoints: List<Vec3>,
    val labels: List<SketchLabel>,
    val centerLabel: SketchCenterLabel?,
    /** One-line summary for captions and notes, e.g. "Box 0.80m × 0.60m · 0.48m²". */
    val summary: String
)

object SketchEngine {

    const val MAX_FREEHAND_POINTS = 240
    const val CIRCLE_SAMPLES = 36
    const val BLUEPRINT_MAX_POINTS = 24

    /** A freehand path counts as closed when its ends are this close (fraction of the width). */
    const val CLOSE_LOOP_FRACTION = 0.06f

    /** Lines within this many degrees of horizontal / vertical snap onto the axis while drawing. */
    const val AXIS_SNAP_DEG = 3.0

    // ---------------------------------------------------------------------------------------
    // Shape geometry (normalized coordinates; aspect = viewport height / width)
    // ---------------------------------------------------------------------------------------

    /** Corners TL, TR, BR, BL of a box drawn between two opposite corners. */
    fun boxCorners(a: NormPoint, b: NormPoint): List<NormPoint> {
        val x0 = min(a.x, b.x)
        val x1 = max(a.x, b.x)
        val y0 = min(a.y, b.y)
        val y1 = max(a.y, b.y)
        return listOf(NormPoint(x0, y0), NormPoint(x1, y0), NormPoint(x1, y1), NormPoint(x0, y1))
    }

    /** Circle radius in viewport widths (screen space is isotropic in widths). */
    fun radiusInWidths(center: NormPoint, rim: NormPoint, aspect: Float): Float =
        hypot(rim.x - center.x, (rim.y - center.y) * aspect)

    /** Points on the on-screen circle, starting at [rim] and going around. */
    fun circleRim(center: NormPoint, rim: NormPoint, aspect: Float, samples: Int = CIRCLE_SAMPLES): List<NormPoint> {
        val r = radiusInWidths(center, rim, aspect)
        val start = atan2(((rim.y - center.y) * aspect).toDouble(), (rim.x - center.x).toDouble())
        return (0 until samples).map { k ->
            val t = start + 2.0 * PI * k / samples
            NormPoint(
                center.x + (r * cos(t)).toFloat(),
                center.y + (r * sin(t)).toFloat() / aspect
            )
        }
    }

    fun isClosedLoop(points: List<NormPoint>, aspect: Float): Boolean {
        if (points.size < 6) return false
        val first = points.first()
        val last = points.last()
        val gap = hypot(last.x - first.x, (last.y - first.y) * aspect)
        return gap <= CLOSE_LOOP_FRACTION && pathLengthInWidths(points, aspect) > CLOSE_LOOP_FRACTION * 4f
    }

    private fun pathLengthInWidths(points: List<NormPoint>, aspect: Float): Float {
        var sum = 0f
        for (i in 1 until points.size) {
            sum += hypot(points[i].x - points[i - 1].x, (points[i].y - points[i - 1].y) * aspect)
        }
        return sum
    }

    /** Evenly picks at most [maxPoints] points, always keeping the first and last. */
    fun downsample(points: List<NormPoint>, maxPoints: Int): List<NormPoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        return (0 until maxPoints).map { points[(it.toLong() * (points.size - 1) / (maxPoints - 1)).toInt()] }
    }

    // ---------------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------------

    /** Measures [shape] on [scale]; null when part of the shape isn't over a known surface. */
    fun measure(
        shape: SketchShape,
        scale: SketchScale,
        unit: LengthDisplayUnit,
        aspect: Float
    ): SketchMeasurement? {
        val f = SpatialMeasurementEngine.UnitFormatter(unit)
        return when (shape.tool) {
            SketchTool.LINE -> measureLine(shape.points, scale, f)
            SketchTool.RECTANGLE -> measureBox(shape.points, scale, f)
            SketchTool.CIRCLE -> measureCircle(shape.points, scale, f, aspect)
            SketchTool.FREEHAND -> measurePath(shape.points, scale, f, aspect)
        }
    }

    private fun mapAll(points: List<NormPoint>, scale: SketchScale): List<Vec3>? {
        val out = ArrayList<Vec3>(points.size)
        for (p in points) out.add(scale.worldPoint(p) ?: return null)
        return out
    }

    private fun measureLine(
        pts: List<NormPoint>,
        scale: SketchScale,
        f: SpatialMeasurementEngine.UnitFormatter
    ): SketchMeasurement? {
        if (pts.size < 2) return null
        val world = mapAll(pts.take(2), scale) ?: return null
        val length = f.length(world[0].distanceTo(world[1]))
        return SketchMeasurement(
            result = SpatialMeasurementEngine.calculate(ShapeMode.LINE, world, f.unit),
            worldPoints = world,
            labels = listOf(SketchLabel(pts[0], pts[1], length)),
            centerLabel = null,
            summary = "Line $length"
        )
    }

    private fun measureBox(
        pts: List<NormPoint>,
        scale: SketchScale,
        f: SpatialMeasurementEngine.UnitFormatter
    ): SketchMeasurement? {
        if (pts.size < 2) return null
        val corners = boxCorners(pts[0], pts[1])
        val w = mapAll(corners, scale) ?: return null
        // On a frozen AR frame the screen box covers a general quadrilateral of the surface,
        // so opposite edges are averaged and the area is the true quadrilateral area.
        val top = w[0].distanceTo(w[1])
        val right = w[1].distanceTo(w[2])
        val bottom = w[2].distanceTo(w[3])
        val left = w[3].distanceTo(w[0])
        val width = (top + bottom) / 2.0
        val height = (left + right) / 2.0
        val diagonal = (w[0].distanceTo(w[2]) + w[1].distanceTo(w[3])) / 2.0
        val perimeter = top + right + bottom + left
        val area = SpatialMeasurementEngine.polygonArea(w)
        val result = ShapeMeasurementResult(
            shapeMode = ShapeMode.RECTANGLE,
            primaryLabel = "Surface",
            primaryValue = f.areaValue(area),
            primaryUnitSymbol = f.unit.areaSymbol,
            secondaryMetrics = listOf(
                "W" to f.length(width),
                "H" to f.length(height),
                "Diag" to f.length(diagonal),
                "P" to f.length(perimeter)
            ),
            edgeLabelsCm = listOf(top, right, bottom, left).map { it * 100.0 },
            categoryName = MeasureCategory.AREA.label,
            surfaceAreaDisplay = f.area(area),
            volumeDisplay = SpatialMeasurementEngine.NOT_APPLICABLE,
            perimeterDisplay = f.length(perimeter)
        )
        return SketchMeasurement(
            result = result,
            worldPoints = w,
            labels = listOf(
                SketchLabel(corners[0], corners[1], f.length(top)),
                SketchLabel(corners[1], corners[2], f.length(right))
            ),
            centerLabel = SketchCenterLabel(centroid(corners), f.area(area)),
            summary = "Box ${f.length(width)} × ${f.length(height)} · ${f.area(area)}"
        )
    }

    private fun measureCircle(
        pts: List<NormPoint>,
        scale: SketchScale,
        f: SpatialMeasurementEngine.UnitFormatter,
        aspect: Float
    ): SketchMeasurement? {
        if (pts.size < 2) return null
        val center = pts[0]
        val rimPoint = pts[1]
        val worldCenter = scale.worldPoint(center) ?: return null
        val worldRim = mapAll(circleRim(center, rimPoint, aspect), scale) ?: return null
        // Mean radius: a screen circle on a tilted surface covers an ellipse-like outline.
        val radius = worldRim.sumOf { it.distanceTo(worldCenter) } / worldRim.size
        val direction = (worldRim[0] - worldCenter).normalized()
        if (radius < 1e-6 || direction == Vec3.ZERO) return null
        val world = listOf(worldCenter, worldCenter + direction * radius)
        val diameter = f.length(radius * 2.0)
        val r = radiusInWidths(center, rimPoint, aspect)
        return SketchMeasurement(
            result = SpatialMeasurementEngine.calculate(ShapeMode.CIRCLE, world, f.unit),
            worldPoints = world,
            labels = listOf(
                SketchLabel(NormPoint(center.x - r, center.y), NormPoint(center.x + r, center.y), "Ø $diameter")
            ),
            centerLabel = SketchCenterLabel(
                NormPoint(center.x, center.y + r * 0.5f / aspect),
                f.area(PI * radius * radius)
            ),
            summary = "Circle Ø $diameter"
        )
    }

    private fun measurePath(
        pts: List<NormPoint>,
        scale: SketchScale,
        f: SpatialMeasurementEngine.UnitFormatter,
        aspect: Float
    ): SketchMeasurement? {
        if (pts.size < 2) return null
        val world = mapAll(pts, scale) ?: return null
        var length = 0.0
        for (i in 1 until world.size) length += world[i].distanceTo(world[i - 1])

        if (isClosedLoop(pts, aspect)) {
            val perimeter = length + world.last().distanceTo(world.first())
            val area = SpatialMeasurementEngine.polygonArea(world)
            val coarse = downsampleWorld(world, 64)
            var span = 0.0
            for (i in coarse.indices) for (j in i + 1 until coarse.size) span = max(span, coarse[i].distanceTo(coarse[j]))
            val result = ShapeMeasurementResult(
                shapeMode = ShapeMode.POLYGON,
                primaryLabel = "Traced Area",
                primaryValue = f.areaValue(area),
                primaryUnitSymbol = f.unit.areaSymbol,
                secondaryMetrics = listOf(
                    "Perim" to f.length(perimeter),
                    "Span" to f.length(span)
                ),
                edgeLabelsCm = emptyList(),
                categoryName = MeasureCategory.AREA.label,
                surfaceAreaDisplay = f.area(area),
                volumeDisplay = SpatialMeasurementEngine.NOT_APPLICABLE,
                perimeterDisplay = f.length(perimeter)
            )
            return SketchMeasurement(
                result = result,
                worldPoints = downsampleWorld(world, BLUEPRINT_MAX_POINTS),
                labels = emptyList(),
                centerLabel = SketchCenterLabel(centroid(pts), f.area(area)),
                summary = "Traced ${f.area(area)} · P ${f.length(perimeter)}"
            )
        }

        val span = world.first().distanceTo(world.last())
        val result = ShapeMeasurementResult(
            shapeMode = ShapeMode.LINE,
            primaryLabel = "Path",
            primaryValue = f.lengthValue(length),
            primaryUnitSymbol = f.unit.symbol,
            secondaryMetrics = listOf("Span" to f.length(span)),
            edgeLabelsCm = listOf(length * 100.0),
            categoryName = MeasureCategory.LENGTH.label,
            surfaceAreaDisplay = SpatialMeasurementEngine.NOT_APPLICABLE,
            volumeDisplay = SpatialMeasurementEngine.NOT_APPLICABLE,
            perimeterDisplay = f.length(length)
        )
        val mid = pts[pts.size / 2]
        return SketchMeasurement(
            result = result,
            worldPoints = listOf(world.first(), world.last()),
            labels = listOf(SketchLabel(mid, mid, f.length(length))),
            centerLabel = null,
            summary = "Path ${f.length(length)}"
        )
    }

    private fun downsampleWorld(points: List<Vec3>, maxPoints: Int): List<Vec3> {
        if (points.size <= maxPoints) return points
        return (0 until maxPoints).map { points[(it.toLong() * (points.size - 1) / (maxPoints - 1)).toInt()] }
    }

    private fun centroid(points: List<NormPoint>): NormPoint =
        NormPoint(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

    /** Normalized points in the layout the history blueprint expects for the shape's result mode. */
    fun blueprintPoints(shape: SketchShape, aspect: Float): List<NormPoint> = when (shape.tool) {
        SketchTool.LINE, SketchTool.CIRCLE -> shape.points.take(2)
        SketchTool.RECTANGLE -> if (shape.points.size >= 2) boxCorners(shape.points[0], shape.points[1]) else emptyList()
        SketchTool.FREEHAND -> when {
            isClosedLoop(shape.points, aspect) -> downsample(shape.points, BLUEPRINT_MAX_POINTS)
            shape.points.size >= 2 -> listOf(shape.points.first(), shape.points.last())
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Drawing & editing helpers
    // ---------------------------------------------------------------------------------------

    /** Snaps a line's end onto the horizontal / vertical through [start] when nearly aligned. */
    fun snapLineEnd(start: NormPoint, end: NormPoint, width: Int, height: Int): NormPoint {
        val dx = ((end.x - start.x) * width).toDouble()
        val dy = ((end.y - start.y) * height).toDouble()
        if (hypot(dx, dy) < 1.0) return end
        val deg = abs(Math.toDegrees(atan2(dy, dx)))
        return when {
            deg <= AXIS_SNAP_DEG || deg >= 180.0 - AXIS_SNAP_DEG -> NormPoint(end.x, start.y)
            abs(deg - 90.0) <= AXIS_SNAP_DEG -> NormPoint(start.x, end.y)
            else -> end
        }
    }

    /** Appends [p] to a freehand path once it is [minStepPx] away from the last point. */
    fun appendPathPoint(points: List<NormPoint>, p: NormPoint, minStepPx: Float, width: Int, height: Int): List<NormPoint> {
        val last = points.lastOrNull()
        if (last != null && hypot((p.x - last.x) * width, (p.y - last.y) * height) < minStepPx) return points
        val base = if (points.size >= MAX_FREEHAND_POINTS) downsample(points, MAX_FREEHAND_POINTS / 2) else points
        return base + p
    }

    /** True when a just-drawn shape is too small to be intentional. */
    fun isTooSmall(shape: SketchShape, width: Int, height: Int, minPx: Float): Boolean {
        val pts = shape.points
        if (pts.size < 2) return true
        fun dx(a: NormPoint, b: NormPoint) = abs(a.x - b.x) * width
        fun dy(a: NormPoint, b: NormPoint) = abs(a.y - b.y) * height
        return when (shape.tool) {
            SketchTool.LINE, SketchTool.CIRCLE -> hypot(dx(pts[0], pts[1]), dy(pts[0], pts[1])) < minPx
            SketchTool.RECTANGLE -> dx(pts[0], pts[1]) < minPx || dy(pts[0], pts[1]) < minPx
            SketchTool.FREEHAND -> {
                var length = 0f
                for (i in 1 until pts.size) length += hypot(dx(pts[i], pts[i - 1]), dy(pts[i], pts[i - 1]))
                length < minPx * 2f
            }
        }
    }

    /** Draggable handles of [shape] (freehand paths have none). */
    fun handles(shape: SketchShape): List<NormPoint> {
        val pts = shape.points
        if (pts.size < 2) return emptyList()
        return when (shape.tool) {
            SketchTool.LINE, SketchTool.CIRCLE -> pts.take(2)
            SketchTool.RECTANGLE -> {
                val a = pts[0]
                val b = pts[1]
                listOf(a, NormPoint(b.x, a.y), b, NormPoint(a.x, b.y))
            }
            SketchTool.FREEHAND -> emptyList()
        }
    }

    /** Moves handle [index] (see [handles]) to [to]. A circle's center handle moves the circle. */
    fun moveHandle(shape: SketchShape, index: Int, to: NormPoint): SketchShape {
        val t = NormPoint(to.x.coerceIn(0f, 1f), to.y.coerceIn(0f, 1f))
        val pts = shape.points
        if (pts.size < 2) return shape
        val a = pts[0]
        val b = pts[1]
        val moved = when (shape.tool) {
            SketchTool.LINE -> if (index == 0) listOf(t, b) else listOf(a, t)
            SketchTool.RECTANGLE -> when (index) {
                0 -> listOf(t, b)
                1 -> listOf(NormPoint(a.x, t.y), NormPoint(t.x, b.y))
                2 -> listOf(a, t)
                3 -> listOf(NormPoint(t.x, a.y), NormPoint(b.x, t.y))
                else -> pts
            }
            SketchTool.CIRCLE -> if (index == 0) {
                return translate(shape, t.x - a.x, t.y - a.y)
            } else {
                listOf(a, t)
            }
            SketchTool.FREEHAND -> pts
        }
        return shape.copy(points = moved)
    }

    /** Moves the whole shape, stopping at the viewport edges. */
    fun translate(shape: SketchShape, dx: Float, dy: Float): SketchShape {
        val pts = shape.points
        if (pts.isEmpty()) return shape
        val cdx = dx.coerceIn(-pts.minOf { it.x }, 1f - pts.maxOf { it.x })
        val cdy = dy.coerceIn(-pts.minOf { it.y }, 1f - pts.maxOf { it.y })
        return shape.copy(points = pts.map { NormPoint(it.x + cdx, it.y + cdy) })
    }

    // ---------------------------------------------------------------------------------------
    // Hit testing (pixels)
    // ---------------------------------------------------------------------------------------

    /** Distance in pixels from (x, y) to the drawn outline of [shape]. */
    fun strokeDistancePx(shape: SketchShape, x: Float, y: Float, width: Int, height: Int): Float {
        val pts = shape.points
        if (pts.isEmpty()) return Float.MAX_VALUE
        fun sx(p: NormPoint) = p.x * width
        fun sy(p: NormPoint) = p.y * height
        fun segment(a: NormPoint, b: NormPoint) = segmentDistance(x, y, sx(a), sy(a), sx(b), sy(b))
        return when (shape.tool) {
            SketchTool.LINE -> if (pts.size >= 2) segment(pts[0], pts[1]) else Float.MAX_VALUE
            SketchTool.RECTANGLE -> {
                if (pts.size < 2) return Float.MAX_VALUE
                val c = boxCorners(pts[0], pts[1])
                (0 until 4).minOf { segment(c[it], c[(it + 1) % 4]) }
            }
            SketchTool.CIRCLE -> {
                if (pts.size < 2) return Float.MAX_VALUE
                val radius = hypot(sx(pts[1]) - sx(pts[0]), sy(pts[1]) - sy(pts[0]))
                abs(hypot(x - sx(pts[0]), y - sy(pts[0])) - radius)
            }
            SketchTool.FREEHAND -> {
                if (pts.size == 1) return hypot(x - sx(pts[0]), y - sy(pts[0]))
                var best = Float.MAX_VALUE
                for (i in 1 until pts.size) best = min(best, segment(pts[i - 1], pts[i]))
                if (isClosedLoop(pts, height.toFloat() / width)) best = min(best, segment(pts.last(), pts.first()))
                best
            }
        }
    }

    /** True when (x, y) lies inside a closed shape. */
    fun containsPx(shape: SketchShape, x: Float, y: Float, width: Int, height: Int): Boolean {
        val pts = shape.points
        if (pts.size < 2) return false
        return when (shape.tool) {
            SketchTool.LINE -> false
            SketchTool.RECTANGLE -> {
                val nx = x / width
                val ny = y / height
                nx in min(pts[0].x, pts[1].x)..max(pts[0].x, pts[1].x) &&
                    ny in min(pts[0].y, pts[1].y)..max(pts[0].y, pts[1].y)
            }
            SketchTool.CIRCLE -> {
                val cx = pts[0].x * width
                val cy = pts[0].y * height
                hypot(x - cx, y - cy) < hypot(pts[1].x * width - cx, pts[1].y * height - cy)
            }
            SketchTool.FREEHAND -> {
                if (!isClosedLoop(pts, height.toFloat() / width)) return false
                var inside = false
                var j = pts.size - 1
                for (i in pts.indices) {
                    val xi = pts[i].x * width
                    val yi = pts[i].y * height
                    val xj = pts[j].x * width
                    val yj = pts[j].y * height
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
                inside
            }
        }
    }

    /** The shape under (x, y): the nearest outline within [tolerancePx], else the topmost enclosing one. */
    fun pick(shapes: List<SketchShape>, x: Float, y: Float, width: Int, height: Int, tolerancePx: Float): SketchShape? {
        var best: SketchShape? = null
        var bestDistance = tolerancePx
        shapes.forEach { shape ->
            val d = strokeDistancePx(shape, x, y, width, height)
            if (d <= bestDistance) {
                bestDistance = d
                best = shape
            }
        }
        return best ?: shapes.lastOrNull { containsPx(it, x, y, width, height) }
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
