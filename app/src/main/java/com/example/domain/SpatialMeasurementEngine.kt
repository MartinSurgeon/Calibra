package com.example.domain

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Where a measurement's geometry came from. Persisted on `MeasurementEntity.source`. */
enum class MeasurementSource(val label: String, val shortLabel: String) {
    CALIBRATED("Calibrated camera estimate", "2D"),
    ARCORE("ARCore world-tracked", "AR");

    companion object {
        fun fromName(raw: String?): MeasurementSource =
            entries.firstOrNull { it.name == raw } ?: CALIBRATED
    }
}

/**
 * World-space measurement maths for ARCore-tracked points.
 *
 * All inputs are meters in ARCore world space (+Y up, opposite gravity). Results are returned as
 * [ShapeMeasurementResult] in the user's [LengthDisplayUnit] so the rest of the UI (telemetry,
 * save dialog, history, exports) works unchanged for both the AR and the calibrated 2D engines.
 *
 * Point conventions per mode:
 * - LINE: start, end
 * - RECTANGLE: corner A, corner B, any point on the opposite side (4th corner is derived)
 * - CIRCLE: center, rim
 * - POLYGON: ordered vertices (implicitly closed)
 * - ANGLE: arm A end, vertex, arm B end
 * - SNAP_3D (box / room): base corner A, base corner B, base opposite side, top (height)
 */
object SpatialMeasurementEngine {

    const val MAX_POLYGON_POINTS = 24
    const val NOT_APPLICABLE = "—"

    /** Number of placed points that completes a shape, or null for open-ended (polygon). */
    fun requiredPoints(mode: ShapeMode): Int? = when (mode) {
        ShapeMode.LINE -> 2
        ShapeMode.RECTANGLE -> 3
        ShapeMode.CIRCLE -> 2
        ShapeMode.ANGLE -> 3
        ShapeMode.SNAP_3D -> 4
        ShapeMode.POLYGON -> null
    }

    /** Next-step coaching line for the AR HUD. */
    fun stepHint(mode: ShapeMode, placed: Int): String = when (mode) {
        ShapeMode.LINE ->
            if (placed == 0) "Aim at the start point and tap +" else "Aim at the end point and tap +"
        ShapeMode.RECTANGLE -> when (placed) {
            0 -> "Tap + on the first corner"
            1 -> "Tap + on the next corner along one side"
            else -> "Sweep across to the opposite side and tap +"
        }
        ShapeMode.CIRCLE ->
            if (placed == 0) "Tap + on the center of the circle" else "Tap + on the rim"
        ShapeMode.POLYGON ->
            if (placed < 3) "Tap + on each corner of the area"
            else "Aim at the first point or press DONE to close"
        ShapeMode.ANGLE -> when (placed) {
            0 -> "Tap + at the end of the first arm"
            1 -> "Tap + on the corner (vertex)"
            else -> "Tap + at the end of the second arm"
        }
        ShapeMode.SNAP_3D -> when (placed) {
            0 -> "Tap + on a base corner of the box or room"
            1 -> "Tap + on the next base corner"
            2 -> "Sweep across the base depth and tap +"
            else -> "Tilt up to the top edge and tap + to set the height"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Geometry primitives
    // ---------------------------------------------------------------------------------------

    /**
     * Completes a rectangle from two adjacent corners [a], [b] and any point [c] on the opposite
     * side. The side vector is made perpendicular to AB so the result is a true rectangle.
     */
    fun rectangleCorners(a: Vec3, b: Vec3, c: Vec3): List<Vec3> {
        val ab = b - a
        val abLen2 = ab dot ab
        val bc = c - b
        val side = if (abLen2 < 1e-12) bc else bc - ab * ((bc dot ab) / abLen2)
        return listOf(a, b, b + side, a + side)
    }

    /**
     * Newell's method: a vector normal to the polygon whose length equals twice its area.
     * Robust for slightly non-planar input (gives the area projected on the best-fit plane).
     */
    fun newellNormal(points: List<Vec3>): Vec3 {
        if (points.size < 3) return Vec3.ZERO
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (i in points.indices) {
            val cur = points[i]
            val nxt = points[(i + 1) % points.size]
            nx += (cur.y - nxt.y) * (cur.z + nxt.z)
            ny += (cur.z - nxt.z) * (cur.x + nxt.x)
            nz += (cur.x - nxt.x) * (cur.y + nxt.y)
        }
        return Vec3(nx, ny, nz)
    }

    fun polygonArea(points: List<Vec3>): Double = newellNormal(points).length * 0.5

    /** Angle at [vertex] between rays to [a] and [b], in degrees (0..180). */
    fun angleAt(a: Vec3, vertex: Vec3, b: Vec3): Double {
        val v1 = a - vertex
        val v2 = b - vertex
        val denom = v1.length * v2.length
        if (denom < 1e-12) return 0.0
        val cos = ((v1 dot v2) / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    /** Unit normal of a rectangle base, oriented upward when possible (falls back to +Y). */
    fun baseNormal(base: List<Vec3>): Vec3 {
        if (base.size < 4) return Vec3.UP
        val n = ((base[1] - base[0]) cross (base[3] - base[0])).normalized()
        if (n == Vec3.ZERO) return Vec3.UP
        return if (n.y < 0) -n else n
    }

    /** Signed height of [top] above the rectangle [base] along its normal. */
    fun boxHeight(base: List<Vec3>, top: Vec3): Double {
        if (base.size < 4) return 0.0
        return (top - base[2]) dot baseNormal(base)
    }

    /** The 8 corners of the box (base 0..3, top 4..7) defined by 3 base points + a top point. */
    fun boxCorners(points: List<Vec3>): List<Vec3> {
        if (points.size < 4) return emptyList()
        val base = rectangleCorners(points[0], points[1], points[2])
        val n = baseNormal(base)
        val h = boxHeight(base, points[3])
        return base + base.map { it + n * h }
    }

    /**
     * Parameter t of the point on the axis `origin + dir * t` that is closest to the camera ray
     * `rayOrigin + rayDir * s` (s >= 0). Used to measure heights by tilting the phone up.
     * Returns null when the ray is (nearly) parallel to the axis.
     */
    fun closestAxisParameter(origin: Vec3, dir: Vec3, rayOrigin: Vec3, rayDir: Vec3): Double? {
        val u = dir.normalized()
        val v = rayDir.normalized()
        val w0 = origin - rayOrigin
        val b = u dot v
        val d = u dot w0
        val e = v dot w0
        val denom = 1.0 - b * b
        if (denom < 1e-6) return null
        val s = (e - b * d) / denom
        if (s < 0) return null // axis is behind the camera
        return (b * e - d) / denom
    }

    // ---------------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------------

    fun calculate(
        mode: ShapeMode,
        points: List<Vec3>,
        displayUnit: LengthDisplayUnit
    ): ShapeMeasurementResult {
        val f = UnitFormatter(displayUnit)
        return when (mode) {
            ShapeMode.LINE -> line(points, f)
            ShapeMode.RECTANGLE -> rectangle(points, f)
            ShapeMode.CIRCLE -> circle(points, f)
            ShapeMode.POLYGON -> polygon(points, f)
            ShapeMode.ANGLE -> angle(points, f)
            ShapeMode.SNAP_3D -> box(points, f)
        }
    }

    private fun line(pts: List<Vec3>, f: UnitFormatter): ShapeMeasurementResult {
        val a = pts.getOrNull(0)
        val b = pts.getOrNull(1)
        val dist = if (a != null && b != null) a.distanceTo(b) else 0.0
        val rise = if (a != null && b != null) abs(b.y - a.y) else 0.0
        val run = if (a != null && b != null) hypot(b.x - a.x, b.z - a.z) else 0.0
        val slopeDeg = if (dist > 1e-6) Math.toDegrees(atan2(rise, run)) else 0.0
        return ShapeMeasurementResult(
            shapeMode = ShapeMode.LINE,
            primaryLabel = "Length",
            primaryValue = f.lengthValue(dist),
            primaryUnitSymbol = f.unit.symbol,
            secondaryMetrics = listOf(
                "Run" to f.length(run),
                "Rise" to f.length(rise),
                "Slope" to "${UnitConverterEngine.formatValue(slopeDeg, 1)}°"
            ),
            edgeLabelsCm = listOf(dist * 100.0),
            categoryName = MeasureCategory.LENGTH.label,
            surfaceAreaDisplay = NOT_APPLICABLE,
            volumeDisplay = NOT_APPLICABLE,
            perimeterDisplay = f.length(dist)
        )
    }

    private fun rectangle(pts: List<Vec3>, f: UnitFormatter): ShapeMeasurementResult {
        val w = if (pts.size >= 2) pts[0].distanceTo(pts[1]) else 0.0
        val h = if (pts.size >= 3) {
            val corners = rectangleCorners(pts[0], pts[1], pts[2])
            corners[1].distanceTo(corners[2])
        } else 0.0
        val area = w * h
        val perimeter = 2.0 * (w + h)
        return ShapeMeasurementResult(
            shapeMode = ShapeMode.RECTANGLE,
            primaryLabel = "Surface",
            primaryValue = f.areaValue(area),
            primaryUnitSymbol = f.unit.areaSymbol,
            secondaryMetrics = listOf(
                "W" to f.length(w),
                "H" to f.length(h),
                "Diag" to f.length(hypot(w, h)),
                "P" to f.length(perimeter)
            ),
            edgeLabelsCm = listOf(w, h, w, h).map { it * 100.0 },
            categoryName = MeasureCategory.AREA.label,
            surfaceAreaDisplay = f.area(area),
            volumeDisplay = NOT_APPLICABLE,
            perimeterDisplay = f.length(perimeter)
        )
    }

    private fun circle(pts: List<Vec3>, f: UnitFormatter): ShapeMeasurementResult {
        val radius = if (pts.size >= 2) pts[0].distanceTo(pts[1]) else 0.0
        val area = PI * radius * radius
        val circumference = 2.0 * PI * radius
        return ShapeMeasurementResult(
            shapeMode = ShapeMode.CIRCLE,
            primaryLabel = "Diameter",
            primaryValue = f.lengthValue(radius * 2.0),
            primaryUnitSymbol = f.unit.symbol,
            secondaryMetrics = listOf(
                "Radius" to f.length(radius),
                "Circ" to f.length(circumference),
                "Area" to f.area(area)
            ),
            edgeLabelsCm = listOf(radius * 200.0),
            categoryName = MeasureCategory.LENGTH.label,
            surfaceAreaDisplay = f.area(area),
            volumeDisplay = NOT_APPLICABLE,
            perimeterDisplay = f.length(circumference)
        )
    }

    private fun polygon(pts: List<Vec3>, f: UnitFormatter): ShapeMeasurementResult {
        val edges = when {
            pts.size >= 3 -> pts.indices.map { i -> pts[i].distanceTo(pts[(i + 1) % pts.size]) }
            pts.size == 2 -> listOf(pts[0].distanceTo(pts[1]))
            else -> emptyList()
        }
        val perimeter = edges.sum()
        val area = polygonArea(pts)
        return ShapeMeasurementResult(
            shapeMode = ShapeMode.POLYGON,
            primaryLabel = "Polygon Area",
            primaryValue = f.areaValue(area),
            primaryUnitSymbol = f.unit.areaSymbol,
            secondaryMetrics = listOf(
                "Perim" to f.length(perimeter),
                "Sides" to "${if (pts.size >= 3) pts.size else 0}",
                "Max" to f.length(edges.maxOrNull() ?: 0.0)
            ),
            edgeLabelsCm = edges.map { it * 100.0 },
            categoryName = MeasureCategory.AREA.label,
            surfaceAreaDisplay = f.area(area),
            volumeDisplay = NOT_APPLICABLE,
            perimeterDisplay = f.length(perimeter)
        )
    }

    private fun angle(pts: List<Vec3>, f: UnitFormatter): ShapeMeasurementResult {
        val armA = if (pts.size >= 2) pts[0].distanceTo(pts[1]) else 0.0
        val armB = if (pts.size >= 3) pts[2].distanceTo(pts[1]) else 0.0
        val deg = if (pts.size >= 3) angleAt(pts[0], pts[1], pts[2]) else 0.0
        val rad = Math.toRadians(deg)
        return ShapeMeasurementResult(
            shapeMode = ShapeMode.ANGLE,
            primaryLabel = "Angle",
            primaryValue = deg,
            primaryUnitSymbol = "°",
            secondaryMetrics = listOf(
                "Rad" to "${UnitConverterEngine.formatValue(rad, 3)} rad",
                "Arm A" to f.length(armA),
                "Arm B" to f.length(armB)
            ),
            edgeLabelsCm = listOf(armA * 100.0, armB * 100.0),
            categoryName = MeasureCategory.ANGLE.label,
            surfaceAreaDisplay = NOT_APPLICABLE,
            volumeDisplay = NOT_APPLICABLE,
            perimeterDisplay = f.length(armA + armB)
        )
    }

    private fun box(pts: List<Vec3>, f: UnitFormatter): ShapeMeasurementResult {
        val w = if (pts.size >= 2) pts[0].distanceTo(pts[1]) else 0.0
        val base = if (pts.size >= 3) rectangleCorners(pts[0], pts[1], pts[2]) else emptyList()
        val d = if (base.size == 4) base[1].distanceTo(base[2]) else 0.0
        val h = if (pts.size >= 4) abs(boxHeight(base, pts[3])) else 0.0
        val floor = w * d
        val volume = floor * h
        val surface = 2.0 * (w * d + w * h + d * h)
        return ShapeMeasurementResult(
            shapeMode = ShapeMode.SNAP_3D,
            primaryLabel = "Volume",
            primaryValue = f.volumeValue(volume),
            primaryUnitSymbol = f.unit.volumeSymbol,
            secondaryMetrics = listOf(
                "W" to f.length(w),
                "D" to f.length(d),
                "H" to f.length(h),
                "Floor" to f.area(floor)
            ),
            edgeLabelsCm = listOf(w, d, h).map { it * 100.0 },
            categoryName = MeasureCategory.VOLUME.label,
            surfaceAreaDisplay = f.area(surface),
            volumeDisplay = f.volume(volume),
            perimeterDisplay = f.length(2.0 * (w + d))
        )
    }

    // ---------------------------------------------------------------------------------------
    // Blueprint (true-proportion 2D drawing for history thumbnails, PDF and the 3D preview)
    // ---------------------------------------------------------------------------------------

    /**
     * Flattens world points into normalized 0..1 drawing coordinates in the same layout the
     * calibrated engine uses, so `BlueprintThumbnail`, the PDF report and `IsometricRoomPreview`
     * render AR measurements with their true proportions.
     */
    fun blueprint(mode: ShapeMode, points: List<Vec3>): List<NormPoint> {
        if (points.size < 2) return emptyList()
        return when (mode) {
            ShapeMode.LINE -> {
                // Side elevation: horizontal run vs vertical rise, so slopes read correctly.
                val d = points[1] - points[0]
                fit(listOf(0.0 to 0.0, hypot(d.x, d.z) to d.y))
            }
            ShapeMode.CIRCLE -> listOf(NormPoint(0.5f, 0.5f), NormPoint(0.84f, 0.5f))
            ShapeMode.SNAP_3D -> {
                val w = points[0].distanceTo(points[1])
                val base = if (points.size >= 3) rectangleCorners(points[0], points[1], points[2]) else emptyList()
                val d = if (base.size == 4) base[1].distanceTo(base[2]) else 0.0
                val h = if (points.size >= 4) abs(boxHeight(base, points[3])) else 0.0
                // Oblique cabinet projection: front face (W x H) + receding right wing (D).
                val ox = d * 0.5 * cos(PI / 6)
                val oy = d * 0.5 * sin(PI / 6)
                fit(
                    listOf(
                        0.0 to h, w to h, w to 0.0, 0.0 to 0.0,
                        w + ox to h + oy, w + ox to oy
                    )
                )
            }
            ShapeMode.RECTANGLE -> {
                val corners = if (points.size >= 3) rectangleCorners(points[0], points[1], points[2]) else points
                planarFit(corners)
            }
            ShapeMode.POLYGON, ShapeMode.ANGLE -> planarFit(points)
        }
    }

    private fun planarFit(points: List<Vec3>): List<NormPoint> {
        val origin = points[0]
        val u = (points[1] - origin).normalized()
        if (u == Vec3.ZERO) return emptyList()
        var n = newellNormal(points).normalized()
        if (n == Vec3.ZERO || abs(n dot u) > 0.99) {
            // Collinear input: use any axis perpendicular to u.
            n = (if (abs(u.y) < 0.9) u cross Vec3.UP else u cross Vec3(1.0, 0.0, 0.0)).normalized()
        }
        val v = (n cross u).normalized()
        return fit(points.map { p -> ((p - origin) dot u) to ((p - origin) dot v) })
    }

    /** Scales 2D points (y up) into a centered 0.1..0.9 box (y down), preserving aspect ratio. */
    private fun fit(coords: List<Pair<Double, Double>>): List<NormPoint> {
        val minX = coords.minOf { it.first }
        val maxX = coords.maxOf { it.first }
        val minY = coords.minOf { it.second }
        val maxY = coords.maxOf { it.second }
        val span = max(max(maxX - minX, maxY - minY), 1e-6)
        val scale = 0.8 / span
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        return coords.map { (x, y) ->
            NormPoint(
                x = (0.5 + (x - cx) * scale).toFloat().coerceIn(0f, 1f),
                y = (0.5 - (y - cy) * scale).toFloat().coerceIn(0f, 1f)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    fun serialize(points: List<Vec3>): String = points.joinToString(";") {
        String.format(Locale.US, "%.4f,%.4f,%.4f", it.x, it.y, it.z)
    }

    fun deserialize(raw: String): List<Vec3> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size != 3) return@mapNotNull null
            val x = parts[0].trim().toDoubleOrNull()
            val y = parts[1].trim().toDoubleOrNull()
            val z = parts[2].trim().toDoubleOrNull()
            if (x != null && y != null && z != null) Vec3(x, y, z) else null
        }
    }

    /** Formats SI values into the user's display unit (cm-based [LengthDisplayUnit] factors). */
    class UnitFormatter(val unit: LengthDisplayUnit) {
        private val cmPerUnit = unit.cmPerUnit

        fun lengthValue(meters: Double) = meters * 100.0 / cmPerUnit
        fun areaValue(sqMeters: Double) = sqMeters * 10_000.0 / (cmPerUnit * cmPerUnit)
        fun volumeValue(cuMeters: Double) = cuMeters * 1_000_000.0 / (cmPerUnit * cmPerUnit * cmPerUnit)

        fun length(meters: Double) = "${UnitConverterEngine.formatValue(lengthValue(meters), 2)}${unit.symbol}"
        fun area(sqMeters: Double) = "${UnitConverterEngine.formatValue(areaValue(sqMeters), 2)}${unit.areaSymbol}"
        fun volume(cuMeters: Double) = "${UnitConverterEngine.formatValue(volumeValue(cuMeters), 2)}${unit.volumeSymbol}"
    }

    /** Largest point-to-point displacement between two equally sized point lists (meters). */
    fun maxDeviation(a: List<Vec3>, b: List<Vec3>): Double {
        if (a.size != b.size) return Double.MAX_VALUE
        var worst = 0.0
        for (i in a.indices) worst = max(worst, a[i].distanceTo(b[i]))
        return worst
    }
}
