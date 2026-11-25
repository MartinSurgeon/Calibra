package com.example.domain

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectedOutline(
    val points: List<NormPoint>,
    val confidence: Float, // 0f..1f
    val statusLabel: String
)

/**
 * Real-time Computer Vision Shape Outline Analyzer for CameraX ImageAnalysis.
 *
 * Processes Y-plane luminance on a fast downsampled grid using Sobel gradient magnitudes,
 * adaptive edge thresholding, and contour extraction to outline detected objects in real time
 * according to the user's chosen [ShapeMode].
 */
class ShapeOutlineAnalyzer(
    private val currentModeProvider: () -> ShapeMode,
    private val isEnabledProvider: () -> Boolean,
    private val onOutlineDetected: (DetectedOutline) -> Unit
) : ImageAnalysis.Analyzer {

    private var smoothedPoints: List<NormPoint> = emptyList()
    private var lastMode: ShapeMode? = null
    private var lastProcessedTimeMs: Long = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (!isEnabledProvider()) return

            val now = System.currentTimeMillis()
            if (now - lastProcessedTimeMs < 75L) return // ~13 Hz smooth tracking without battery drain
            lastProcessedTimeMs = now

            val mode = currentModeProvider()
            if (mode != lastMode) {
                smoothedPoints = emptyList()
                lastMode = mode
            }

            val yPlane = image.planes.firstOrNull() ?: return
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val srcW = image.width
            val srcH = image.height
            if (srcW < 16 || srcH < 16) return

            val gridW = 64
            val gridH = 48
            val lum = FloatArray(gridW * gridH)

            val rotation = image.imageInfo.rotationDegrees

            // Downsample Y-plane into oriented portrait/landscape grid
            for (gy in 0 until gridH) {
                val ny = (gy + 0.5f) / gridH
                for (gx in 0 until gridW) {
                    val nx = (gx + 0.5f) / gridW
                    val (sxNorm, syNorm) = mapRotation(nx, ny, rotation)
                    val sx = (sxNorm * (srcW - 1)).toInt().coerceIn(0, srcW - 1)
                    val sy = (syNorm * (srcH - 1)).toInt().coerceIn(0, srcH - 1)
                    val index = sy * rowStride + sx * pixelStride
                    val byteVal = if (index in 0 until buffer.capacity()) {
                        buffer.get(index).toInt() and 0xFF
                    } else 0
                    lum[gy * gridW + gx] = byteVal.toFloat()
                }
            }

            // Compute Sobel gradient magnitude on interior cells (ignore outer 12% margin to focus on target)
            val marginX = (gridW * 0.12f).toInt().coerceAtLeast(2)
            val marginY = (gridH * 0.12f).toInt().coerceAtLeast(2)
            val mags = FloatArray(gridW * gridH)
            var sum = 0.0
            var sumSq = 0.0
            var count = 0

            for (y in marginY until (gridH - marginY)) {
                for (x in marginX until (gridW - marginX)) {
                    val tl = lum[(y - 1) * gridW + (x - 1)]
                    val tc = lum[(y - 1) * gridW + x]
                    val tr = lum[(y - 1) * gridW + (x + 1)]
                    val ml = lum[y * gridW + (x - 1)]
                    val mr = lum[y * gridW + (x + 1)]
                    val bl = lum[(y + 1) * gridW + (x - 1)]
                    val bc = lum[(y + 1) * gridW + x]
                    val br = lum[(y + 1) * gridW + (x + 1)]

                    val gxVal = -tl + tr - 2f * ml + 2f * mr - bl + br
                    val gyVal = -tl - 2f * tc - tr + bl + 2f * bc + br
                    val mag = sqrt(gxVal * gxVal + gyVal * gyVal)
                    mags[y * gridW + x] = mag
                    sum += mag
                    sumSq += mag * mag
                    count++
                }
            }

            if (count == 0) return
            val mean = sum / count
            val variance = max(0.0, (sumSq / count) - (mean * mean))
            val stdDev = sqrt(variance)
            val threshold = (mean + 1.25 * stdDev).toFloat().coerceAtLeast(38f)

            val edgePoints = ArrayList<NormPoint>(256)
            for (y in marginY until (gridH - marginY)) {
                for (x in marginX until (gridW - marginX)) {
                    if (mags[y * gridW + x] >= threshold) {
                        edgePoints.add(
                            NormPoint(
                                x = (x + 0.5f) / gridW,
                                y = (y + 0.5f) / gridH
                            )
                        )
                    }
                }
            }

            if (edgePoints.size < 14) {
                onOutlineDetected(
                    DetectedOutline(
                        points = smoothedPoints.ifEmpty { GeometryEngine.defaultPointsFor(mode) },
                        confidence = 0.15f,
                        statusLabel = "SCANNING"
                    )
                )
                return
            }

            // Compute trimmed bounding box (5th..95th percentile) to reject stray noise edges
            val xs = edgePoints.map { it.x }.sorted()
            val ys = edgePoints.map { it.y }.sorted()
            val lowIdx = (xs.size * 0.06f).toInt().coerceIn(0, xs.lastIndex)
            val highIdx = (xs.size * 0.94f).toInt().coerceIn(lowIdx, xs.lastIndex)

            val minX = xs[lowIdx].coerceIn(0.10f, 0.85f)
            val maxX = xs[highIdx].coerceIn(minX + 0.08f, 0.92f)
            val minY = ys[lowIdx].coerceIn(0.10f, 0.85f)
            val maxY = ys[highIdx].coerceIn(minY + 0.08f, 0.92f)
            val cx = (minX + maxX) * 0.5f
            val cy = (minY + maxY) * 0.5f

            val rawShapePoints = when (mode) {
                ShapeMode.SNAP_3D -> {
                    val wingRight = (maxX + 0.14f).coerceAtMost(0.92f)
                    val wingTop = (minY - 0.10f).coerceAtLeast(0.12f)
                    val wingBottom = (maxY + 0.08f).coerceAtMost(0.76f)
                    listOf(
                        NormPoint(minX, minY),
                        NormPoint(maxX, minY),
                        NormPoint(maxX, maxY),
                        NormPoint(minX, maxY),
                        NormPoint(wingRight, wingTop),
                        NormPoint(wingRight, wingBottom)
                    )
                }

                ShapeMode.RECTANGLE -> listOf(
                    NormPoint(minX, minY),
                    NormPoint(maxX, minY),
                    NormPoint(maxX, maxY),
                    NormPoint(minX, maxY)
                )

                ShapeMode.CIRCLE -> {
                    val radius = ((maxX - minX) * 0.5f + (maxY - minY) * 0.5f) * 0.48f
                    val clampedR = radius.coerceIn(0.08f, 0.38f)
                    listOf(
                        NormPoint(cx, cy),
                        NormPoint((cx + clampedR).coerceIn(0.1f, 0.94f), cy)
                    )
                }

                ShapeMode.LINE -> {
                    // Lock onto the widest dominant span of the detected feature
                    val width = maxX - minX
                    val height = maxY - minY
                    if (width >= height) {
                        listOf(NormPoint(minX, cy), NormPoint(maxX, cy))
                    } else {
                        listOf(NormPoint(cx, minY), NormPoint(cx, maxY))
                    }
                }

                ShapeMode.POLYGON -> extractRadialContour(edgePoints, cx, cy, sectors = 6)

                ShapeMode.ANGLE -> listOf(
                    NormPoint(minX, maxY),
                    NormPoint(minX, minY),
                    NormPoint(maxX, minY)
                )
            }

            // Exponential moving average smoothing for rock-steady AR outlines
            val alpha = 0.28f
            val nextSmoothed = if (smoothedPoints.size == rawShapePoints.size) {
                smoothedPoints.zip(rawShapePoints) { prev, curr ->
                    NormPoint(
                        x = prev.x + (curr.x - prev.x) * alpha,
                        y = prev.y + (curr.y - prev.y) * alpha
                    )
                }
            } else {
                rawShapePoints
            }
            smoothedPoints = nextSmoothed

            val densityScore = (edgePoints.size.toFloat() / (count * 0.14f)).coerceIn(0f, 1f)
            val contrastScore = ((stdDev.toFloat() - 12f) / 48f).coerceIn(0f, 1f)
            val confidence = (0.45f * densityScore + 0.55f * contrastScore).coerceIn(0.2f, 0.98f)
            val status = when {
                confidence >= 0.65f -> "LOCKED"
                confidence >= 0.40f -> "TRACKING"
                else -> "SCANNING"
            }

            onOutlineDetected(
                DetectedOutline(
                    points = nextSmoothed,
                    confidence = confidence,
                    statusLabel = status
                )
            )
        } finally {
            image.close()
        }
    }

    private fun extractRadialContour(
        edgePoints: List<NormPoint>,
        cx: Float,
        cy: Float,
        sectors: Int
    ): List<NormPoint> {
        val sectorMax = arrayOfNulls<NormPoint>(sectors)
        val sectorMaxDist = FloatArray(sectors) { -1f }

        for (p in edgePoints) {
            val dx = p.x - cx
            val dy = p.y - cy
            val dist = hypot(dx, dy)
            if (dist < 0.04f) continue
            var angle = atan2(dy.toDouble(), dx.toDouble())
            if (angle < 0) angle += 2.0 * PI
            val idx = ((angle / (2.0 * PI)) * sectors).toInt().coerceIn(0, sectors - 1)
            if (dist > sectorMaxDist[idx]) {
                sectorMaxDist[idx] = dist
                sectorMax[idx] = p
            }
        }

        val fallbackR = 0.20f
        return (0 until sectors).map { idx ->
            sectorMax[idx] ?: run {
                val theta = (idx + 0.5) * (2.0 * PI / sectors)
                NormPoint(
                    x = (cx + cos(theta).toFloat() * fallbackR).coerceIn(0.10f, 0.90f),
                    y = (cy + sin(theta).toFloat() * fallbackR).coerceIn(0.10f, 0.90f)
                )
            }
        }
    }

    private fun mapRotation(nx: Float, ny: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
}
