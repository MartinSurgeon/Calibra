package com.example.ui.measure

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ar.ArCenterLabel
import com.example.ar.ArFrameState
import com.example.ar.ArRenderShape
import com.example.ar.ArReticle
import com.example.ar.ArSessionController
import com.example.ar.ArSurfaceKind
import com.example.domain.LengthDisplayUnit
import com.example.domain.ShapeMode
import com.example.domain.SpatialMeasurementEngine
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.CalibraVisualTokens
import com.example.ui.theme.LocalCalibraTokens
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Live ARCore viewport: a [GLSurfaceView] streaming the camera through [ArSessionController]
 * with a Compose overlay that draws detected surfaces, the hit-test reticle and every
 * world-anchored measurement, re-projected every frame.
 */
@Composable
fun ArCoreViewport(
    controller: ArSessionController,
    shapeMode: ShapeMode,
    displayUnit: LengthDisplayUnit,
    showPlanes: Boolean,
    captureHandle: ViewportCaptureHandle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tokens = LocalCalibraTokens.current
    val density = LocalDensity.current
    val glView = remember { GLSurfaceView(context).also(controller::configureView) }

    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onHostResume(glView)
                Lifecycle.Event.ON_PAUSE -> controller.onHostPause(glView)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.onHostPause(glView)
        }
    }

    SideEffect {
        controller.setMode(shapeMode)
        controller.setPlanesVisible(showPlanes)
    }

    val frameState = controller.frameState.collectAsState()
    val latestUnit by rememberUpdatedState(displayUnit)
    val latestTokens by rememberUpdatedState(tokens)

    DisposableEffect(captureHandle, controller) {
        val provider: ViewportFrameProvider = provider@{ withOverlay ->
            val capture = controller.captureFrame() ?: return@provider null
            if (!withOverlay) return@provider capture.bitmap
            renderOverlayOnto(capture.bitmap, density) {
                drawArSceneOverlay(
                    state = capture.state,
                    displayUnit = latestUnit,
                    tokens = latestTokens,
                    showPlanes = false,
                    showReticle = false
                )
            }
        }
        captureHandle.register(provider)
        onDispose { captureHandle.unregister(provider) }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF15181E))
            .testTag("arcore_viewport")
            .semantics { contentDescription = "Live ARCore camera with world-anchored ${shapeMode.label} measurement" }
    ) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Reading the state here (not in composition) limits per-frame work to the draw pass.
            drawArSceneOverlay(
                state = frameState.value,
                displayUnit = displayUnit,
                tokens = tokens,
                showPlanes = showPlanes,
                showReticle = true
            )
        }
    }
}

/** Maps GL view pixels onto the current canvas (identical sizes in practice). */
private class PixelMapper(private val sx: Float, private val sy: Float) {
    fun map(o: Offset) = Offset(o.x * sx, o.y * sy)
}

internal fun DrawScope.drawArSceneOverlay(
    state: ArFrameState,
    displayUnit: LengthDisplayUnit,
    tokens: CalibraVisualTokens,
    showPlanes: Boolean,
    showReticle: Boolean
) {
    if (state.viewWidth <= 0 || state.viewHeight <= 0) return
    val px = PixelMapper(size.width / state.viewWidth, size.height / state.viewHeight)
    val formatter = SpatialMeasurementEngine.UnitFormatter(displayUnit)

    if (showPlanes) {
        val outlineEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        state.planeOutlines.forEach { outline ->
            if (outline.size < 3) return@forEach
            val path = Path().apply {
                val first = px.map(outline[0])
                moveTo(first.x, first.y)
                for (i in 1 until outline.size) {
                    val p = px.map(outline[i])
                    lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.05f))
            drawPath(path, Color.White.copy(alpha = 0.3f), style = Stroke(width = 2f, pathEffect = outlineEffect))
        }
        if (state.planeDots.isNotEmpty()) {
            drawPoints(
                points = state.planeDots.map(px::map),
                pointMode = PointMode.Points,
                color = Color.White.copy(alpha = 0.55f),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }
    }

    state.shapes.forEach { drawArShape(it, formatter, tokens, px) }

    if (showReticle) state.reticle?.let { drawArReticle(it, tokens, px) }
}

private fun DrawScope.drawArShape(
    shape: ArRenderShape,
    formatter: SpatialMeasurementEngine.UnitFormatter,
    tokens: CalibraVisualTokens,
    px: PixelMapper
) {
    val lineColor = if (shape.isActive) tokens.laserPrimary else tokens.laserPrimary.copy(alpha = 0.82f)
    val core = when {
        tokens.highContrast -> 6f
        shape.isActive -> 4.4f
        else -> 3.4f
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(20f, 14f))

    if (shape.fill.size >= 3) {
        val path = Path().apply {
            val first = px.map(shape.fill[0])
            moveTo(first.x, first.y)
            for (i in 1 until shape.fill.size) {
                val p = px.map(shape.fill[i])
                lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(path, Color.White.copy(alpha = if (shape.isActive) 0.12f else 0.07f))
    }

    shape.edges.forEach { e ->
        val a = px.map(e.a)
        val b = px.map(e.b)
        val effect = if (e.dashed) dash else null
        drawLine(Color(0x55000000), a, b, strokeWidth = core + 4f, cap = StrokeCap.Round, pathEffect = effect)
        drawLine(lineColor, a, b, strokeWidth = core, cap = StrokeCap.Round, pathEffect = effect)
    }

    shape.edges.forEach { e ->
        val lengthM = e.lengthM ?: return@forEach
        val a = px.map(e.a)
        val b = px.map(e.b)
        if (hypot(b.x - a.x, b.y - a.y) < 70f) return@forEach
        val raw = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
        val angle = when {
            raw > 90f -> raw - 180f
            raw < -90f -> raw + 180f
            else -> raw
        }
        drawWhiteDimensionPill(
            center = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f),
            text = formatter.length(lengthM),
            angleDeg = angle,
            highContrast = tokens.highContrast
        )
    }

    shape.centerLabel?.let { label ->
        val text = when (label.kind) {
            ArCenterLabel.Kind.AREA -> "S=${formatter.area(label.value)}"
            ArCenterLabel.Kind.VOLUME -> "V=${formatter.volume(label.value)}"
            ArCenterLabel.Kind.ANGLE -> "${UnitConverterEngine.formatValue(label.value, 1)}°"
        }
        drawSurfaceCenterWatermark(px.map(label.position), text)
    }

    val nodeScale = if (shape.isActive) 1f else 0.78f
    shape.nodes.forEach { node ->
        val p = px.map(node)
        drawCircle(Color(0x55000000), radius = 19f * nodeScale, center = Offset(p.x, p.y + 2f))
        drawCircle(Color.White, radius = 15.5f * nodeScale, center = p)
        drawCircle(tokens.arNodeBlue, radius = 10f * nodeScale, center = p)
    }
}

private fun DrawScope.drawArReticle(
    reticle: ArReticle,
    tokens: CalibraVisualTokens,
    px: PixelMapper
) {
    val c = px.map(reticle.center)
    if (reticle.kind == null) {
        // Searching: dashed ring + crosshair at the screen center.
        val muted = Color.White.copy(alpha = 0.6f)
        drawCircle(
            color = muted,
            radius = 36f,
            center = c,
            style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)))
        )
        drawLine(muted, Offset(c.x - 14f, c.y), Offset(c.x + 14f, c.y), strokeWidth = 2.5f, cap = StrokeCap.Round)
        drawLine(muted, Offset(c.x, c.y - 14f), Offset(c.x, c.y + 14f), strokeWidth = 2.5f, cap = StrokeCap.Round)
        return
    }

    val accent = when {
        reticle.kind == ArSurfaceKind.NODE -> tokens.arNodeBlue
        reticle.axisLock != null || reticle.kind == ArSurfaceKind.HEIGHT -> tokens.laserSecondary
        else -> Color.White
    }

    if (reticle.ring.size >= 3) {
        val path = Path().apply {
            val first = px.map(reticle.ring[0])
            moveTo(first.x, first.y)
            for (i in 1 until reticle.ring.size) {
                val p = px.map(reticle.ring[i])
                lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(path, accent.copy(alpha = 0.14f))
        drawPath(path, Color(0x44000000), style = Stroke(width = 7f, join = StrokeJoin.Round))
        drawPath(path, accent, style = Stroke(width = 3.5f, join = StrokeJoin.Round))
    } else {
        drawCircle(Color(0x44000000), radius = 22f, center = c, style = Stroke(width = 7f))
        drawCircle(accent, radius = 22f, center = c, style = Stroke(width = 3.5f))
        if (reticle.kind == ArSurfaceKind.HEIGHT) {
            // Up/down carets: the height follows the phone's tilt.
            drawLine(accent, Offset(c.x - 10f, c.y - 34f), Offset(c.x, c.y - 44f), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(accent, Offset(c.x + 10f, c.y - 34f), Offset(c.x, c.y - 44f), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(accent, Offset(c.x - 10f, c.y + 34f), Offset(c.x, c.y + 44f), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(accent, Offset(c.x + 10f, c.y + 34f), Offset(c.x, c.y + 44f), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
    drawCircle(Color(0x66000000), radius = 7f, center = c)
    drawCircle(accent, radius = 5f, center = c)

    val tag = reticle.axisLock ?: if (reticle.kind == ArSurfaceKind.NODE) "SNAP" else null
    if (tag != null) {
        val bg = if (reticle.kind == ArSurfaceKind.NODE) 0xFFBFDBFE.toInt() else 0xFFA7F3D0.toInt()
        drawContext.canvas.nativeCanvas.drawTag(c.x, c.y - 64f, tag, bg)
    }
}
