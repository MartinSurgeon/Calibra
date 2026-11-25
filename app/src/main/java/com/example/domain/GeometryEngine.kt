package com.example.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ShapeMode(val label: String, val shortHint: String) {
    SNAP_3D("3D Snap", "Room & multi-plane 3D surface/volume"),
    RECTANGLE("Rect", "Width, height & surface area"),
    LINE("Line", "Point-to-point distance"),
    CIRCLE("Circle", "Diameter, radius & area"),
    POLYGON("Polygon", "Multi-vertex perimeter & area"),
    ANGLE("Angle", "3-point precision protractor")
}

enum class LengthDisplayUnit(
    val id: String,
    val symbol: String,
    val areaSymbol: String,
    val volumeSymbol: String,
    val cmPerUnit: Double
) {
    MM("mm", "mm", "mm²", "mm³", 0.1),
    CM("cm", "cm", "cm²", "cm³", 1.0),
    M("m", "m", "m²", "m³", 100.0),
    IN("in", "in", "in²", "in³", 2.54),
    FT("ft", "ft", "ft²", "ft³", 30.48);

    companion object {
        fun fromId(id: String): LengthDisplayUnit =
            entries.firstOrNull { it.id == id } ?: M
    }
}

enum class CalibrationPreset(
    val label: String,
    val knownCm: Double,
    val description: String
) {
    CREDIT_CARD("Credit / ID Card", 8.56, "ISO/IEC 7810 width (85.6 mm)"),
    US_QUARTER("US Quarter Coin", 2.426, "Diameter (24.26 mm)"),
    EURO_COIN("1 Euro Coin", 2.325, "Diameter (23.25 mm)"),
    A4_WIDTH("A4 Paper Width", 21.0, "Standard A4 short edge (210 mm)"),
    US_LETTER("US Letter Width", 21.59, "Standard Letter width (8.5 in)"),
    DOOR_WIDTH("Standard Door", 91.4, "Interior door frame width (91.4 cm)"),
    CUSTOM("Custom Object", 10.0, "Enter your exact known length")
}

data class NormPoint(
    val x: Float,
    val y: Float
)

data class CalibrationProfile(
    /** Real-world centimeters corresponding to the full width (1.0f) of the viewport at current calibration. */
    val cmPerNormWidth: Float = 360f,
    val workingDistanceCm: Float = 240f,
    val referenceName: String = CalibrationPreset.DOOR_WIDTH.label,
    val tiltCompensationEnabled: Boolean = true,
    val lastCalibratedAt: Long = 0L
)

data class ShapeMeasurementResult(
    val shapeMode: ShapeMode,
    val primaryLabel: String,
    val primaryValue: Double,
    val primaryUnitSymbol: String,
    val secondaryMetrics: List<Pair<String, String>>,
    val edgeLabelsCm: List<Double>,
    val categoryName: String,
    val surfaceAreaDisplay: String,
    val volumeDisplay: String,
    val perimeterDisplay: String
) {
    val formattedPrimary: String
        get() = "${UnitConverterEngine.formatValue(primaryValue, 2)}$primaryUnitSymbol"

    val secondarySummary: String
        get() = secondaryMetrics.joinToString(" • ") { "${it.first}: ${it.second}" }
}

object GeometryEngine {

    fun defaultPointsFor(mode: ShapeMode): List<NormPoint> = when (mode) {
        ShapeMode.SNAP_3D -> listOf(
            // Back wall rectangle (0..3) + right receding wing (4..5) matching architectural 3D room snap
            NormPoint(0.18f, 0.28f), // 0: Back top-left
            NormPoint(0.68f, 0.28f), // 1: Back top-right
            NormPoint(0.68f, 0.58f), // 2: Back bottom-right
            NormPoint(0.18f, 0.58f), // 3: Back bottom-left
            NormPoint(0.84f, 0.17f), // 4: Front-right ceiling corner
            NormPoint(0.84f, 0.65f)  // 5: Front-right floor corner
        )
        ShapeMode.LINE -> listOf(
            NormPoint(0.20f, 0.48f),
            NormPoint(0.80f, 0.48f)
        )
        ShapeMode.RECTANGLE -> listOf(
            NormPoint(0.20f, 0.25f),
            NormPoint(0.76f, 0.25f),
            NormPoint(0.76f, 0.58f),
            NormPoint(0.20f, 0.58f)
        )
        ShapeMode.CIRCLE -> listOf(
            NormPoint(0.48f, 0.42f), // center
            NormPoint(0.72f, 0.42f)  // radius handle
        )
        ShapeMode.POLYGON -> listOf(
            NormPoint(0.48f, 0.22f),
            NormPoint(0.74f, 0.36f),
            NormPoint(0.68f, 0.58f),
            NormPoint(0.28f, 0.58f),
            NormPoint(0.22f, 0.36f)
        )
        ShapeMode.ANGLE -> listOf(
            NormPoint(0.22f, 0.56f), // arm A
            NormPoint(0.48f, 0.56f), // vertex
            NormPoint(0.72f, 0.28f)  // arm B
        )
    }

    fun calculate(
        mode: ShapeMode,
        points: List<NormPoint>,
        aspectRatioHeightOverWidth: Float,
        calibration: CalibrationProfile,
        displayUnit: LengthDisplayUnit,
        tiltDegrees: Float = 0f,
        extrusionDepthCm: Float = 260f
    ): ShapeMeasurementResult {
        val ratio = clampAspect(aspectRatioHeightOverWidth)
        val cmPerWidth = effectiveCmPerWidth(calibration, tiltDegrees)

        fun toCm(a: NormPoint, b: NormPoint): Double {
            val dx = (b.x - a.x) * cmPerWidth
            val dy = (b.y - a.y) * ratio * cmPerWidth
            return hypot(dx.toDouble(), dy.toDouble())
        }

        fun fmtLen(cm: Double): String {
            val converted = cm / displayUnit.cmPerUnit
            return "${UnitConverterEngine.formatValue(converted, 2)}${displayUnit.symbol}"
        }

        fun fmtArea(cm2: Double): String {
            val unitSq = displayUnit.cmPerUnit * displayUnit.cmPerUnit
            val converted = cm2 / unitSq
            return "${UnitConverterEngine.formatValue(converted, 2)}${displayUnit.areaSymbol}"
        }

        fun fmtVol(cm3: Double): String {
            val unitCube = displayUnit.cmPerUnit * displayUnit.cmPerUnit * displayUnit.cmPerUnit
            val converted = cm3 / unitCube
            return "${UnitConverterEngine.formatValue(converted, 2)}${displayUnit.volumeSymbol}"
        }

        return when (mode) {
            ShapeMode.SNAP_3D -> {
                val pts = if (points.size >= 6) points else defaultPointsFor(ShapeMode.SNAP_3D)
                val backW = toCm(pts[0], pts[1])
                val backH = toCm(pts[0], pts[3])
                val depthEdgeTop = toCm(pts[1], pts[4]) * 1.45 // perspective foreshortening depth projection
                val depthEdgeBottom = toCm(pts[2], pts[5]) * 1.45
                val depthCm = ((depthEdgeTop + depthEdgeBottom) * 0.5).coerceAtLeast(10.0)
                val rightH = toCm(pts[4], pts[5])
                val avgH = (backH + rightH) * 0.5

                val floorAreaCm2 = backW * depthCm
                val wallSurfaceCm2 = 2.0 * (backW + depthCm) * avgH + floorAreaCm2
                val volumeCm3 = floorAreaCm2 * avgH
                val perimeterCm = 2.0 * (backW + depthCm)

                val areaConverted = wallSurfaceCm2 / (displayUnit.cmPerUnit * displayUnit.cmPerUnit)
                ShapeMeasurementResult(
                    shapeMode = mode,
                    primaryLabel = "Surface",
                    primaryValue = areaConverted,
                    primaryUnitSymbol = displayUnit.areaSymbol,
                    secondaryMetrics = listOf(
                        "W" to fmtLen(backW),
                        "H" to fmtLen(avgH),
                        "D" to fmtLen(depthCm),
                        "Vol" to fmtVol(volumeCm3)
                    ),
                    edgeLabelsCm = listOf(backW, backH, depthCm, rightH),
                    categoryName = MeasureCategory.AREA.label,
                    surfaceAreaDisplay = fmtArea(wallSurfaceCm2),
                    volumeDisplay = fmtVol(volumeCm3),
                    perimeterDisplay = fmtLen(perimeterCm)
                )
            }

            ShapeMode.LINE -> {
                val p0 = points.getOrElse(0) { NormPoint(0.25f, 0.5f) }
                val p1 = points.getOrElse(1) { NormPoint(0.75f, 0.5f) }
                val distCm = toCm(p0, p1)
                val dxCm = abs((p1.x - p0.x) * cmPerWidth).toDouble()
                val dyCm = abs((p1.y - p0.y) * ratio * cmPerWidth).toDouble()
                val angleDeg = Math.toDegrees(atan2(((p0.y - p1.y) * ratio).toDouble(), (p1.x - p0.x).toDouble()))
                val estArea = distCm * extrusionDepthCm
                ShapeMeasurementResult(
                    shapeMode = mode,
                    primaryLabel = "Length",
                    primaryValue = distCm / displayUnit.cmPerUnit,
                    primaryUnitSymbol = displayUnit.symbol,
                    secondaryMetrics = listOf(
                        "ΔX" to fmtLen(dxCm),
                        "ΔY" to fmtLen(dyCm),
                        "Slope" to "${UnitConverterEngine.formatValue(abs(angleDeg), 1)}°"
                    ),
                    edgeLabelsCm = listOf(distCm),
                    categoryName = MeasureCategory.LENGTH.label,
                    surfaceAreaDisplay = fmtArea(estArea),
                    volumeDisplay = fmtVol(estArea * distCm * 0.25),
                    perimeterDisplay = fmtLen(distCm)
                )
            }

            ShapeMode.RECTANGLE -> {
                val pts = if (points.size >= 4) points else defaultPointsFor(ShapeMode.RECTANGLE)
                val topCm = toCm(pts[0], pts[1])
                val rightCm = toCm(pts[1], pts[2])
                val bottomCm = toCm(pts[2], pts[3])
                val leftCm = toCm(pts[3], pts[0])
                val avgW = (topCm + bottomCm) / 2.0
                val avgH = (rightCm + leftCm) / 2.0
                val diagCm = toCm(pts[0], pts[2])
                val perimeterCm = topCm + rightCm + bottomCm + leftCm
                val areaCm2 = polygonAreaCm2(pts, cmPerWidth, ratio)
                val depthForVol = min(avgW, avgH).coerceAtLeast(10.0)
                val volumeCm3 = areaCm2 * depthForVol
                val areaConverted = areaCm2 / (displayUnit.cmPerUnit * displayUnit.cmPerUnit)
                ShapeMeasurementResult(
                    shapeMode = mode,
                    primaryLabel = "Surface",
                    primaryValue = areaConverted,
                    primaryUnitSymbol = displayUnit.areaSymbol,
                    secondaryMetrics = listOf(
                        "W" to fmtLen(avgW),
                        "H" to fmtLen(avgH),
                        "Diag" to fmtLen(diagCm),
                        "P" to fmtLen(perimeterCm)
                    ),
                    edgeLabelsCm = listOf(topCm, rightCm, bottomCm, leftCm),
                    categoryName = MeasureCategory.AREA.label,
                    surfaceAreaDisplay = fmtArea(areaCm2),
                    volumeDisplay = fmtVol(volumeCm3),
                    perimeterDisplay = fmtLen(perimeterCm)
                )
            }

            ShapeMode.CIRCLE -> {
                val center = points.getOrElse(0) { NormPoint(0.5f, 0.5f) }
                val rim = points.getOrElse(1) { NormPoint(0.72f, 0.5f) }
                val radiusCm = max(0.01, toCm(center, rim))
                val diameterCm = radiusCm * 2.0
                val circumferenceCm = 2.0 * PI * radiusCm
                val areaCm2 = PI * radiusCm * radiusCm
                val cylVolCm3 = areaCm2 * diameterCm
                ShapeMeasurementResult(
                    shapeMode = mode,
                    primaryLabel = "Diameter",
                    primaryValue = diameterCm / displayUnit.cmPerUnit,
                    primaryUnitSymbol = displayUnit.symbol,
                    secondaryMetrics = listOf(
                        "Radius" to fmtLen(radiusCm),
                        "Circ" to fmtLen(circumferenceCm),
                        "Area" to fmtArea(areaCm2)
                    ),
                    edgeLabelsCm = listOf(diameterCm),
                    categoryName = MeasureCategory.LENGTH.label,
                    surfaceAreaDisplay = fmtArea(areaCm2),
                    volumeDisplay = fmtVol(cylVolCm3),
                    perimeterDisplay = fmtLen(circumferenceCm)
                )
            }

            ShapeMode.POLYGON -> {
                val pts = if (points.size >= 3) points else defaultPointsFor(ShapeMode.POLYGON)
                val edges = pts.indices.map { i -> toCm(pts[i], pts[(i + 1) % pts.size]) }
                val perimeterCm = edges.sum()
                val areaCm2 = polygonAreaCm2(pts, cmPerWidth, ratio)
                val volumeCm3 = areaCm2 * extrusionDepthCm
                val areaConverted = areaCm2 / (displayUnit.cmPerUnit * displayUnit.cmPerUnit)
                ShapeMeasurementResult(
                    shapeMode = mode,
                    primaryLabel = "Polygon Area",
                    primaryValue = areaConverted,
                    primaryUnitSymbol = displayUnit.areaSymbol,
                    secondaryMetrics = listOf(
                        "Perim" to fmtLen(perimeterCm),
                        "Sides" to "${pts.size}",
                        "Max" to fmtLen(edges.maxOrNull() ?: 0.0)
                    ),
                    edgeLabelsCm = edges,
                    categoryName = MeasureCategory.AREA.label,
                    surfaceAreaDisplay = fmtArea(areaCm2),
                    volumeDisplay = fmtVol(volumeCm3),
                    perimeterDisplay = fmtLen(perimeterCm)
                )
            }

            ShapeMode.ANGLE -> {
                val a = points.getOrElse(0) { NormPoint(0.25f, 0.6f) }
                val vertex = points.getOrElse(1) { NormPoint(0.5f, 0.6f) }
                val b = points.getOrElse(2) { NormPoint(0.72f, 0.3f) }
                val v1x = (a.x - vertex.x).toDouble()
                val v1y = ((a.y - vertex.y) * ratio).toDouble()
                val v2x = (b.x - vertex.x).toDouble()
                val v2y = ((b.y - vertex.y) * ratio).toDouble()
                val mag1 = sqrt(v1x * v1x + v1y * v1y).coerceAtLeast(1e-6)
                val mag2 = sqrt(v2x * v2x + v2y * v2y).coerceAtLeast(1e-6)
                val cosTheta = ((v1x * v2x + v1y * v2y) / (mag1 * mag2)).coerceIn(-1.0, 1.0)
                val rad = acos(cosTheta)
                val deg = Math.toDegrees(rad)
                val armACm = toCm(vertex, a)
                val armBCm = toCm(vertex, b)
                val triArea = 0.5 * armACm * armBCm * kotlin.math.sin(rad)
                ShapeMeasurementResult(
                    shapeMode = mode,
                    primaryLabel = "Angle",
                    primaryValue = deg,
                    primaryUnitSymbol = "°",
                    secondaryMetrics = listOf(
                        "Rad" to "${UnitConverterEngine.formatValue(rad, 3)} rad",
                        "Arm A" to fmtLen(armACm),
                        "Arm B" to fmtLen(armBCm)
                    ),
                    edgeLabelsCm = listOf(armACm, armBCm),
                    categoryName = MeasureCategory.ANGLE.label,
                    surfaceAreaDisplay = fmtArea(triArea),
                    volumeDisplay = "${UnitConverterEngine.formatValue(rad, 2)} rad",
                    perimeterDisplay = fmtLen(armACm + armBCm)
                )
            }
        }
    }

    private fun polygonAreaCm2(
        pts: List<NormPoint>,
        cmPerWidth: Float,
        aspectRatio: Float
    ): Double {
        if (pts.size < 3) return 0.0
        var sum = 0.0
        for (i in pts.indices) {
            val curr = pts[i]
            val next = pts[(i + 1) % pts.size]
            val x1 = curr.x * cmPerWidth
            val y1 = curr.y * aspectRatio * cmPerWidth
            val x2 = next.x * cmPerWidth
            val y2 = next.y * aspectRatio * cmPerWidth
            sum += (x1 * y2 - x2 * y1)
        }
        return abs(sum) * 0.5
    }

    /** Viewport aspect (height / width) clamped to the range the calibrated model supports. */
    fun clampAspect(aspectRatioHeightOverWidth: Float): Float = aspectRatioHeightOverWidth.coerceIn(0.4f, 3.0f)

    /** Centimeters spanned by the full viewport width, after optional tilt compensation. */
    fun effectiveCmPerWidth(calibration: CalibrationProfile, tiltDegrees: Float): Float {
        val tiltFactor = if (calibration.tiltCompensationEnabled) {
            val clamped = abs(tiltDegrees).coerceIn(0f, 65f)
            cos(clamped * PI / 180.0).toFloat().coerceIn(0.42f, 1.0f)
        } else 1.0f
        return calibration.cmPerNormWidth * tiltFactor
    }

    fun calibrateFromSpan(knownCm: Double, normalizedSpan: Float): Float {
        val safeSpan = max(0.05f, min(0.98f, normalizedSpan))
        return (knownCm / safeSpan).toFloat().coerceIn(2f, 5000f)
    }

    fun serializePoints(points: List<NormPoint>): String =
        points.joinToString(";") {
            "${String.format(java.util.Locale.US, "%.4f", it.x)},${String.format(java.util.Locale.US, "%.4f", it.y)}"
        }

    fun deserializePoints(raw: String): List<NormPoint> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size == 2) {
                val x = parts[0].toFloatOrNull()
                val y = parts[1].toFloatOrNull()
                if (x != null && y != null) NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else null
            } else null
        }
    }
}
