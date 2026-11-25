package com.example.ui.measure

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.domain.DetectedOutline
import com.example.domain.LengthDisplayUnit
import com.example.domain.NormPoint
import com.example.domain.ShapeMeasurementResult
import com.example.domain.ShapeMode
import com.example.domain.ShapeOutlineAnalyzer
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.CalibraVisualTokens
import com.example.ui.theme.LocalCalibraTokens
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ArViewportCanvas(
    cameraActive: Boolean,
    flashlightEnabled: Boolean,
    showSnapGrid: Boolean,
    shapeMode: ShapeMode,
    points: List<NormPoint>,
    measurementResult: ShapeMeasurementResult,
    displayUnit: LengthDisplayUnit,
    autoOutlineEnabled: Boolean,
    isOutlineFrozen: Boolean,
    hapticEnabled: Boolean,
    onOutlineDetected: (DetectedOutline) -> Unit,
    onViewportAspectRatioChanged: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onMovePoint: (Int, NormPoint) -> Unit,
    onAddPolygonPoint: (NormPoint) -> Unit,
    modifier: Modifier = Modifier,
    captureHandle: ViewportCaptureHandle? = null
) {
    val tokens = LocalCalibraTokens.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }
    var activeHandleIndex by remember { mutableIntStateOf(-1) }
    var cameraError by remember { mutableStateOf(false) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    val latestPoints by rememberUpdatedState(points)
    val latestMode by rememberUpdatedState(shapeMode)
    val latestAuto by rememberUpdatedState(autoOutlineEnabled && !isOutlineFrozen)
    val latestOnDetected by rememberUpdatedState(onOutlineDetected)
    val latestCaptureParams by rememberUpdatedState(
        CalibratedCaptureParams(
            liveCamera = cameraActive && !cameraError,
            flashlight = flashlightEnabled,
            shapeMode = shapeMode,
            points = points,
            result = measurementResult,
            displayUnit = displayUnit,
            tokens = tokens
        )
    )

    // Photo capture: camera frame (or studio backdrop) with the dimension overlay burned in.
    DisposableEffect(captureHandle) {
        val handle = captureHandle
        val provider: suspend () -> Bitmap? = provider@{
            val p = latestCaptureParams
            val w = viewportSize.width
            val h = viewportSize.height
            if (w <= 1 || h <= 1) return@provider null
            val cameraFrame = if (p.liveCamera) runCatching { previewViewRef?.bitmap }.getOrNull() else null
            val base = when {
                cameraFrame == null -> renderOverlayOnto(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), density) {
                    drawStudioBackdrop(p.tokens.highContrast, p.flashlight)
                }
                cameraFrame.width != w || cameraFrame.height != h -> Bitmap.createScaledBitmap(cameraFrame, w, h, true)
                else -> cameraFrame
            }
            renderOverlayOnto(base, density) {
                drawArchitecturalShapeOverlay(
                    mode = p.shapeMode,
                    points = p.points,
                    result = p.result,
                    displayUnit = p.displayUnit,
                    lineColor = p.tokens.laserPrimary,
                    nodeBlue = p.tokens.arNodeBlue,
                    highContrast = p.tokens.highContrast,
                    activeHandleIndex = -1
                )
            }
        }
        handle?.register(provider)
        onDispose { handle?.unregister(provider) }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF15181E))
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    viewportSize = size
                    onViewportAspectRatioChanged(size.height.toFloat() / size.width.toFloat())
                }
            }
            .testTag("ar_viewport_canvas")
            .semantics {
                contentDescription = "Interactive AR camera viewport showing ${shapeMode.label} outline measuring ${measurementResult.formattedPrimary}"
            }
    ) {
        // 1. Live CameraX Feed or Architectural Studio Perspective Backdrop
        if (cameraActive && !cameraError) {
            CameraXPreviewLayer(
                flashlightEnabled = flashlightEnabled,
                modeProvider = { latestMode },
                analyzerEnabledProvider = { latestAuto },
                onOutlineDetected = { latestOnDetected(it) },
                onCameraUnavailable = { cameraError = true },
                onPreviewViewChanged = { previewViewRef = it },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            StudioArchitecturalBackdrop(
                highContrast = tokens.highContrast,
                illuminated = flashlightEnabled,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Crisp Architectural AR Vector Overlay (White Lines, Blue Nodes, White Rotated Pill Tags)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(shapeMode) {
                    detectTapGestures { offset ->
                        if (latestMode == ShapeMode.POLYGON && viewportSize.width > 0 && viewportSize.height > 0) {
                            val nx = (offset.x / viewportSize.width).coerceIn(0.05f, 0.95f)
                            val ny = (offset.y / viewportSize.height).coerceIn(0.10f, 0.80f)
                            if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAddPolygonPoint(NormPoint(nx, ny))
                        }
                    }
                }
                .pointerInput(shapeMode) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            if (viewportSize.width <= 0 || viewportSize.height <= 0) return@detectDragGestures
                            val w = viewportSize.width.toFloat()
                            val h = viewportSize.height.toFloat()
                            val hitRadiusPx = 82f
                            var bestIdx = -1
                            var bestDist = hitRadiusPx
                            latestPoints.forEachIndexed { idx, pt ->
                                val dist = hypot(pt.x * w - startOffset.x, pt.y * h - startOffset.y)
                                if (dist < bestDist) {
                                    bestDist = dist
                                    bestIdx = idx
                                }
                            }
                            activeHandleIndex = bestIdx
                            if (bestIdx >= 0) {
                                onDragStarted()
                                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = { activeHandleIndex = -1 },
                        onDragCancel = { activeHandleIndex = -1 },
                        onDrag = { change, dragAmount ->
                            if (activeHandleIndex >= 0 && viewportSize.width > 0 && viewportSize.height > 0) {
                                change.consume()
                                val current = latestPoints.getOrNull(activeHandleIndex) ?: return@detectDragGestures
                                val nx = current.x + dragAmount.x / viewportSize.width.toFloat()
                                val ny = current.y + dragAmount.y / viewportSize.height.toFloat()
                                onMovePoint(activeHandleIndex, NormPoint(nx, ny))
                            }
                        }
                    )
                }
        ) {
            if (showSnapGrid) {
                drawPerspectiveSnapGrid(alpha = if (cameraActive && !cameraError) 0.16f else 0.24f)
            }

            drawArchitecturalShapeOverlay(
                mode = shapeMode,
                points = points,
                result = measurementResult,
                displayUnit = displayUnit,
                lineColor = tokens.laserPrimary,
                nodeBlue = tokens.arNodeBlue,
                highContrast = tokens.highContrast,
                activeHandleIndex = activeHandleIndex
            )
        }
    }
}

@Composable
private fun CameraXPreviewLayer(
    flashlightEnabled: Boolean,
    modeProvider: () -> ShapeMode,
    analyzerEnabledProvider: () -> Boolean,
    onOutlineDetected: (DetectedOutline) -> Unit,
    onCameraUnavailable: () -> Unit,
    onPreviewViewChanged: (PreviewView?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    DisposableEffect(previewView) {
        onPreviewViewChanged(previewView)
        onDispose { onPreviewViewChanged(null) }
    }

    LaunchedEffect(flashlightEnabled, boundCamera) {
        try {
            val cam = boundCamera ?: return@LaunchedEffect
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(flashlightEnabled)
            }
        } catch (e: Exception) {
            Log.w("CalibraCamera", "Torch toggle failed", e)
        }
    }

    DisposableEffect(lifecycleOwner) {
        // Owned by this effect so a lifecycle-owner change never reuses a shut-down executor.
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val listener = Runnable {
            if (disposed) return@Runnable
            try {
                val cameraProvider = cameraProviderFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            analysisExecutor,
                            ShapeOutlineAnalyzer(
                                currentModeProvider = modeProvider,
                                isEnabledProvider = analyzerEnabledProvider,
                                onOutlineDetected = onOutlineDetected
                            )
                        )
                    }

                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    onCameraUnavailable()
                    return@Runnable
                }

                cameraProvider.unbindAll()
                boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.w("CalibraCamera", "CameraX bind unavailable, falling back to studio spatial surface", e)
                onCameraUnavailable()
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            try {
                boundCamera?.cameraControl?.enableTorch(false)
                provider?.unbindAll()
            } catch (_: Exception) {
            }
            boundCamera = null
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

/** Inputs for a calibrated-mode photo capture, kept fresh via rememberUpdatedState. */
private data class CalibratedCaptureParams(
    val liveCamera: Boolean,
    val flashlight: Boolean,
    val shapeMode: ShapeMode,
    val points: List<NormPoint>,
    val result: ShapeMeasurementResult,
    val displayUnit: LengthDisplayUnit,
    val tokens: CalibraVisualTokens
)

/**
 * Refined architectural interior spatial studio backdrop shown when camera is toggled off
 * or unavailable on a device, featuring soft warm architectural lighting and receding room planes.
 */
@Composable
private fun StudioArchitecturalBackdrop(
    highContrast: Boolean,
    illuminated: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawStudioBackdrop(highContrast, illuminated)
    }
}

private fun DrawScope.drawStudioBackdrop(highContrast: Boolean, illuminated: Boolean) {
    run {
        if (highContrast) {
            drawRect(Color.Black)
            return
        }
        val topTone = if (illuminated) Color(0xFF474E5A) else Color(0xFF282D36)
        val midTone = if (illuminated) Color(0xFF333944) else Color(0xFF1E232B)
        val floorTone = if (illuminated) Color(0xFF262A33) else Color(0xFF141820)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(topTone, midTone, floorTone)
            )
        )

        // Subtle architectural room vanishing-point walls so Drafting Mode feels spatial & refined
        val backLeft = size.width * 0.18f
        val backRight = size.width * 0.68f
        val backTop = size.height * 0.28f
        val backBottom = size.height * 0.58f

        // Back wall subtle recessed plane
        drawRect(
            color = Color(0x16FFFFFF),
            topLeft = Offset(backLeft, backTop),
            size = Size(backRight - backLeft, backBottom - backTop)
        )

        // Right warm architectural timber-tinted wall plane
        val rightWall = Path().apply {
            moveTo(backRight, backTop)
            lineTo(size.width, size.height * 0.08f)
            lineTo(size.width, size.height * 0.72f)
            lineTo(backRight, backBottom)
            close()
        }
        drawPath(
            rightWall,
            brush = Brush.horizontalGradient(
                colors = listOf(Color(0x28A67C52), Color(0x458C6239)),
                startX = backRight,
                endX = size.width
            )
        )

        // Floor plane
        val floor = Path().apply {
            moveTo(backLeft, backBottom)
            lineTo(backRight, backBottom)
            lineTo(size.width, size.height * 0.72f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            lineTo(0f, size.height * 0.68f)
            close()
        }
        drawPath(
            floor,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0x22D6D3CD), Color(0x0D000000)),
                startY = backBottom,
                endY = size.height
            )
        )
    }
}

private fun DrawScope.drawPerspectiveSnapGrid(alpha: Float) {
    val gridColor = Color.White.copy(alpha = alpha)
    val cols = 6
    val rows = 8
    val startY = size.height * 0.14f
    val endY = size.height * 0.68f
    val stepX = size.width / cols
    val stepY = (endY - startY) / rows

    for (i in 1 until cols) {
        val x = i * stepX
        drawLine(gridColor, Offset(x, startY), Offset(x, endY), strokeWidth = 1f)
    }
    for (j in 0..rows) {
        val y = startY + j * stepY
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }

    // Center segmented AR target reticle (from reference screenshot #2)
    val cx = size.width * 0.5f
    val cy = size.height * 0.43f
    val r = 46f
    val reticleColor = Color.White.copy(alpha = (alpha * 2.8f).coerceAtMost(0.75f))
    for (k in 0..3) {
        drawArc(
            color = reticleColor,
            startAngle = k * 90f + 14f,
            sweepAngle = 62f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 2.4f, cap = StrokeCap.Round)
        )
    }
    // Subtle surface point-cloud dots around center reticle
    val dotColor = Color.White.copy(alpha = alpha * 1.4f)
    for (ring in 1..2) {
        val rr = r + ring * 26f
        val count = 12 * ring
        for (i in 0 until count) {
            val a = (2.0 * PI * i / count)
            drawCircle(dotColor, 2.2f, Offset(cx + cos(a).toFloat() * rr, cy + sin(a).toFloat() * rr))
        }
    }
}

private fun DrawScope.drawArchitecturalShapeOverlay(
    mode: ShapeMode,
    points: List<NormPoint>,
    result: ShapeMeasurementResult,
    displayUnit: LengthDisplayUnit,
    lineColor: Color,
    nodeBlue: Color,
    highContrast: Boolean,
    activeHandleIndex: Int
) {
    if (points.isEmpty()) return
    val pxPoints = points.map { Offset(it.x * size.width, it.y * size.height) }
    val coreStroke = if (highContrast) 6.5f else 4.2f
    val shadowStroke = coreStroke + 4.5f

    fun fmtCm(cm: Double): String {
        val converted = cm / displayUnit.cmPerUnit
        return "${UnitConverterEngine.formatValue(converted, 2)}${displayUnit.symbol}"
    }

    fun drawCrispEdge(a: Offset, b: Offset) {
        drawLine(Color(0x55000000), a, b, strokeWidth = shadowStroke, cap = StrokeCap.Round)
        drawLine(lineColor, a, b, strokeWidth = coreStroke, cap = StrokeCap.Round)
    }

    when (mode) {
        ShapeMode.SNAP_3D -> {
            if (pxPoints.size >= 6) {
                val p0 = pxPoints[0] // back top-left
                val p1 = pxPoints[1] // back top-right
                val p2 = pxPoints[2] // back bottom-right
                val p3 = pxPoints[3] // back bottom-left
                val p4 = pxPoints[4] // front-right ceiling
                val p5 = pxPoints[5] // front-right floor

                // Subtle translucent white architectural fill on right wing
                val rightWallPath = Path().apply {
                    moveTo(p1.x, p1.y)
                    lineTo(p4.x, p4.y)
                    lineTo(p5.x, p5.y)
                    lineTo(p2.x, p2.y)
                    close()
                }
                drawPath(rightWallPath, Color.White.copy(alpha = 0.08f))

                // Back wall edges
                drawCrispEdge(p0, p1)
                drawCrispEdge(p1, p2)
                drawCrispEdge(p2, p3)
                drawCrispEdge(p3, p0)

                // Right receding 3D plane edges
                drawCrispEdge(p1, p4)
                drawCrispEdge(p4, p5)
                drawCrispEdge(p5, p2)

                // Left subtle receding architectural guide extensions
                drawLine(Color.White.copy(alpha = 0.65f), p0, Offset(0f, (p0.y - 72f).coerceAtLeast(40f)), 2.5f)
                drawLine(Color.White.copy(alpha = 0.65f), p3, Offset(0f, (p3.y + 44f).coerceAtMost(size.height * 0.78f)), 2.5f)

                // Rotated white dimension pill tags matching reference screenshot #1
                val backW = result.edgeLabelsCm.getOrElse(0) { 248.0 }
                val backH = result.edgeLabelsCm.getOrElse(1) { 330.0 }
                val depthCm = result.edgeLabelsCm.getOrElse(2) { 417.0 }

                // Top horizontal width pill
                drawWhiteDimensionPill(
                    center = Offset((p0.x + p1.x) * 0.5f, p0.y + 28f),
                    text = fmtCm(backW),
                    angleDeg = 0f,
                    highContrast = highContrast
                )
                // Left vertical height pill (rotated -90 deg)
                drawWhiteDimensionPill(
                    center = Offset(p0.x + 30f, (p0.y + p3.y) * 0.5f),
                    text = fmtCm(backH),
                    angleDeg = -90f,
                    highContrast = highContrast
                )
                // Slanted 3D depth pill along upper receding beam
                val depthAngle = Math.toDegrees(atan2((p4.y - p1.y).toDouble(), (p4.x - p1.x).toDouble())).toFloat()
                drawWhiteDimensionPill(
                    center = Offset((p1.x + p4.x) * 0.5f - 24f, (p1.y + p4.y) * 0.5f - 20f),
                    text = fmtCm(depthCm),
                    angleDeg = depthAngle.coerceIn(-75f, 75f),
                    highContrast = highContrast
                )
            }
        }

        ShapeMode.LINE -> {
            if (pxPoints.size >= 2) {
                val a = pxPoints[0]
                val b = pxPoints[1]
                drawCrispEdge(a, b)
                val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                val normalizedAngle = if (angle > 90f) angle - 180f else if (angle < -90f) angle + 180f else angle
                val mid = Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f - 32f)
                drawWhiteDimensionPill(mid, result.formattedPrimary, normalizedAngle, highContrast)
            }
        }

        ShapeMode.RECTANGLE, ShapeMode.POLYGON -> {
            if (pxPoints.size >= 3) {
                val path = Path().apply {
                    moveTo(pxPoints[0].x, pxPoints[0].y)
                    for (i in 1 until pxPoints.size) {
                        lineTo(pxPoints[i].x, pxPoints[i].y)
                    }
                    close()
                }
                drawPath(path, color = Color.White.copy(alpha = 0.09f))
                drawPath(path, color = Color(0x55000000), style = Stroke(width = shadowStroke, join = StrokeJoin.Round))
                drawPath(path, color = lineColor, style = Stroke(width = coreStroke, join = StrokeJoin.Round))

                // Per-edge aligned white dimension pills
                pxPoints.indices.forEach { i ->
                    val a = pxPoints[i]
                    val b = pxPoints[(i + 1) % pxPoints.size]
                    val segLenPx = hypot(b.x - a.x, b.y - a.y)
                    if (segLenPx > 72f) {
                        val edgeCm = result.edgeLabelsCm.getOrNull(i) ?: 0.0
                        val rawAngle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                        val displayAngle = when {
                            rawAngle > 90f -> rawAngle - 180f
                            rawAngle < -90f -> rawAngle + 180f
                            else -> rawAngle
                        }
                        val mid = Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
                        drawWhiteDimensionPill(mid, fmtCm(edgeCm), displayAngle, highContrast)
                    }
                }

                // Centered translucent surface label "S=3.30m²" matching screenshot #2 right!
                val cx = pxPoints.map { it.x }.average().toFloat()
                val cy = pxPoints.map { it.y }.average().toFloat()
                drawSurfaceCenterWatermark(Offset(cx, cy), "S=${result.surfaceAreaDisplay}")
            }
        }

        ShapeMode.CIRCLE -> {
            if (pxPoints.size >= 2) {
                val center = pxPoints[0]
                val rim = pxPoints[1]
                val radiusPx = hypot(rim.x - center.x, rim.y - center.y).coerceAtLeast(18f)
                drawCircle(Color.White.copy(alpha = 0.09f), radius = radiusPx, center = center)
                drawCircle(Color(0x55000000), radius = radiusPx, center = center, style = Stroke(width = shadowStroke))
                drawCircle(lineColor, radius = radiusPx, center = center, style = Stroke(width = coreStroke))

                val opposite = Offset(center.x - (rim.x - center.x), center.y - (rim.y - center.y))
                drawCrispEdge(opposite, rim)
                drawWhiteDimensionPill(
                    Offset(center.x, (center.y - radiusPx - 28f).coerceAtLeast(96f)),
                    "Ø ${result.formattedPrimary}",
                    0f,
                    highContrast
                )
                drawSurfaceCenterWatermark(Offset(center.x, center.y + 34f), "S=${result.surfaceAreaDisplay}")
            }
        }

        ShapeMode.ANGLE -> {
            if (pxPoints.size >= 3) {
                val armA = pxPoints[0]
                val vertex = pxPoints[1]
                val armB = pxPoints[2]
                drawCrispEdge(vertex, armA)
                drawCrispEdge(vertex, armB)

                val angleA = Math.toDegrees(atan2((armA.y - vertex.y).toDouble(), (armA.x - vertex.x).toDouble())).toFloat()
                val angleB = Math.toDegrees(atan2((armB.y - vertex.y).toDouble(), (armB.x - vertex.x).toDouble())).toFloat()
                var sweep = angleB - angleA
                while (sweep > 180f) sweep -= 360f
                while (sweep < -180f) sweep += 360f

                val arcRadius = min(
                    hypot(armA.x - vertex.x, armA.y - vertex.y),
                    hypot(armB.x - vertex.x, armB.y - vertex.y)
                ).coerceIn(42f, 118f) * 0.52f

                drawArc(
                    color = nodeBlue,
                    startAngle = angleA,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(vertex.x - arcRadius, vertex.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )

                val midAngleRad = ((angleA + sweep * 0.5f) * PI / 180.0)
                val labelPos = Offset(
                    vertex.x + (cos(midAngleRad) * (arcRadius + 48f)).toFloat(),
                    vertex.y + (sin(midAngleRad) * (arcRadius + 48f)).toFloat()
                )
                drawWhiteDimensionPill(labelPos, result.formattedPrimary, 0f, highContrast)
            }
        }
    }

    // Draw Signature Electric-Blue Nodes ringed with Pure White Border (matching screenshots!)
    pxPoints.forEachIndexed { idx, pt ->
        val isActive = idx == activeHandleIndex
        val outerR = if (isActive) 28f else 17.5f
        val blueR = if (isActive) 18f else 11.5f

        // Soft drop shadow
        drawCircle(color = Color(0x55000000), radius = outerR + 3f, center = Offset(pt.x, pt.y + 2f))
        // Crisp white ring
        drawCircle(color = Color.White, radius = outerR, center = pt)
        // Electric royal blue inner core
        drawCircle(color = nodeBlue, radius = blueR, center = pt)

        if (isActive) {
            // Precision alignment crosshairs when actively dragging
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = outerR + 18f,
                center = pt,
                style = Stroke(width = 2.5f)
            )
            drawLine(Color.White, Offset(pt.x - outerR - 26f, pt.y), Offset(pt.x - outerR - 6f, pt.y), 2.5f)
            drawLine(Color.White, Offset(pt.x + outerR + 6f, pt.y), Offset(pt.x + outerR + 26f, pt.y), 2.5f)
            drawLine(Color.White, Offset(pt.x, pt.y - outerR - 26f), Offset(pt.x, pt.y - outerR - 6f), 2.5f)
            drawLine(Color.White, Offset(pt.x, pt.y + outerR + 6f), Offset(pt.x, pt.y + outerR + 26f), 2.5f)
        }
    }
}

/**
 * Pure-white rounded dimension pill with crisp dark typography, rotated cleanly along edges
 * just like the architectural labels (`3.30m`, `2.48m`, `4.17m`) in the reference design.
 */
internal fun DrawScope.drawWhiteDimensionPill(
    center: Offset,
    text: String,
    angleDeg: Float,
    highContrast: Boolean
) {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (highContrast) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#111827")
        textSize = 33f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val textW = textPaint.measureText(text)
    val fm = textPaint.fontMetrics
    val textH = fm.descent - fm.ascent
    val padX = 20f
    val padY = 11f
    val pillW = textW + padX * 2f
    val pillH = textH + padY * 2f

    val cx = center.x.coerceIn(pillW * 0.5f + 12f, size.width - pillW * 0.5f - 12f)
    val cy = center.y.coerceIn(pillH * 0.5f + 86f, size.height - pillH * 0.5f - 120f)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(65, 0, 0, 0)
        style = Paint.Style.FILL
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (highContrast) android.graphics.Color.parseColor("#FFEE00") else android.graphics.Color.argb(245, 255, 255, 255)
        style = Paint.Style.FILL
    }

    val halfW = pillW * 0.5f
    val halfH = pillH * 0.5f
    val rect = RectF(-halfW, -halfH, halfW, halfH)
    val shadowRect = RectF(-halfW, -halfH + 4f, halfW, halfH + 4f)

    drawContext.canvas.nativeCanvas.apply {
        save()
        translate(cx, cy)
        rotate(angleDeg)
        drawRoundRect(shadowRect, 20f, 20f, shadowPaint)
        drawRoundRect(rect, 20f, 20f, bgPaint)
        drawText(text, -textW * 0.5f, -halfH + padY - fm.ascent, textPaint)
        restore()
    }
}

internal fun DrawScope.drawSurfaceCenterWatermark(center: Offset, text: String) {
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(120, 0, 0, 0)
        textSize = 42f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 255, 255, 255)
        textSize = 42f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val textW = textPaint.measureText(text)
    val x = (center.x - textW * 0.5f).coerceIn(16f, (size.width - textW - 16f).coerceAtLeast(16f))
    val y = center.y.coerceIn(100f, size.height - 140f)
    drawContext.canvas.nativeCanvas.apply {
        drawText(text, x, y + 3f, shadowPaint)
        drawText(text, x, y, textPaint)
    }
}
