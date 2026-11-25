package com.example.ui.measure

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.domain.LengthDisplayUnit
import com.example.domain.MeasurementSource
import com.example.domain.NormPoint
import com.example.domain.SketchEngine
import com.example.domain.SketchMeasurement
import com.example.domain.SketchScale
import com.example.domain.SketchShape
import com.example.domain.SketchTool
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Stroke colors offered by the sketch toolbar. */
internal val SketchPalette = listOf(
    Color(0xFFFFFFFF),
    Color(0xFF22D3EE),
    Color(0xFFFBBF24),
    Color(0xFF34D399),
    Color(0xFFF472B6)
)

/** A camera frame frozen for sketching and the scale that measures shapes drawn on it. */
class SketchSession(
    val bitmap: Bitmap,
    val scale: SketchScale,
    val source: MeasurementSource,
    /** False when nothing on this frame can be measured (AR had no tracking or surfaces). */
    val measurable: Boolean
) {
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Frame height / width; the overlay uses the same size, so normalized coordinates match. */
    val aspect: Float = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
}

/** Shapes, selection, the in-progress draft and undo history of the sketch overlay. */
@Stable
class SketchEditorState {
    var tool by mutableStateOf(SketchTool.LINE)
    var colorIndex by mutableIntStateOf(0)
        private set
    var shapes by mutableStateOf<List<SketchShape>>(emptyList())
        private set
    var selectedId by mutableStateOf<Long?>(null)

    /** The shape under the user's finger, not yet committed. */
    var draft by mutableStateOf<SketchShape?>(null)
        private set

    private var history by mutableStateOf<List<List<SketchShape>>>(emptyList())
    private var nextId = 1L

    val canUndo: Boolean get() = history.isNotEmpty()
    val selected: SketchShape? get() = selectedId?.let { id -> shapes.firstOrNull { it.id == id } }
    val isFull: Boolean get() = shapes.size >= MAX_SHAPES

    /** 1-based position used for the on-canvas badge, captions and notes. */
    fun numberOf(shape: SketchShape): Int = shapes.indexOfFirst { it.id == shape.id }.let { if (it < 0) shapes.size + 1 else it + 1 }

    /** Records the current shapes so the next change can be undone. */
    fun checkpoint() {
        history = (history + listOf(shapes)).takeLast(MAX_UNDO)
    }

    fun beginDraft(start: NormPoint) {
        val initial = if (tool == SketchTool.FREEHAND) listOf(start) else listOf(start, start)
        draft = SketchShape(nextId++, tool, initial, colorIndex)
    }

    fun updateDraft(point: NormPoint, width: Int, height: Int, minStepPx: Float) {
        val d = draft ?: return
        draft = when (d.tool) {
            SketchTool.LINE -> d.copy(points = listOf(d.points[0], SketchEngine.snapLineEnd(d.points[0], point, width, height)))
            SketchTool.RECTANGLE, SketchTool.CIRCLE -> d.copy(points = listOf(d.points[0], point))
            SketchTool.FREEHAND -> d.copy(points = SketchEngine.appendPathPoint(d.points, point, minStepPx, width, height))
        }
    }

    /** Adds the draft unless it is too small to be intentional; returns the committed shape. */
    fun commitDraft(width: Int, height: Int, minPx: Float): SketchShape? {
        val d = draft ?: return null
        draft = null
        if (isFull || SketchEngine.isTooSmall(d, width, height, minPx)) return null
        checkpoint()
        shapes = shapes + d
        selectedId = d.id
        return d
    }

    fun cancelDraft() {
        draft = null
    }

    /** Replaces a shape in place (no undo checkpoint; call [checkpoint] when an edit starts). */
    fun replace(shape: SketchShape) {
        shapes = shapes.map { if (it.id == shape.id) shape else it }
    }

    /** Picks the next stroke color and applies it to the selected shape. */
    fun cycleColor() {
        colorIndex = (colorIndex + 1) % SketchPalette.size
        selected?.let {
            checkpoint()
            replace(it.copy(colorIndex = colorIndex))
        }
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        checkpoint()
        shapes = shapes.filterNot { it.id == id }
        selectedId = null
    }

    fun clear() {
        if (shapes.isEmpty()) return
        checkpoint()
        shapes = emptyList()
        selectedId = null
    }

    fun undo() {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        shapes = previous
        if (selectedId != null && previous.none { it.id == selectedId }) selectedId = null
    }

    /** Forgets everything drawn (a new frame was frozen); keeps the chosen tool and color. */
    fun reset() {
        shapes = emptyList()
        history = emptyList()
        selectedId = null
        draft = null
    }

    private companion object {
        const val MAX_UNDO = 40
        const val MAX_SHAPES = 24
    }
}

/**
 * The frozen frame with every sketched shape on top. Drag on empty space to draw with the current
 * tool; tap a shape to select it; drag a selected shape's handles to reshape it or its outline
 * to move it. A magnifier follows the finger so endpoints can be placed precisely.
 */
@Composable
internal fun SketchCanvas(
    session: SketchSession,
    editor: SketchEditorState,
    measurements: Map<Long, SketchMeasurement?>,
    displayUnit: LengthDisplayUnit,
    highContrast: Boolean,
    hapticEnabled: Boolean,
    onShapeCommitted: (SketchShape) -> Unit,
    onDraftRejected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val haptics by rememberUpdatedState(hapticEnabled)
    val latestOnCommitted by rememberUpdatedState(onShapeCommitted)
    val latestOnRejected by rememberUpdatedState(onDraftRejected)
    var loupeFocus by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier = modifier
            .testTag("sketch_canvas")
            .pointerInput(editor) {
                val touchTolerance = 26.dp.toPx()
                val minShapePx = 14.dp.toPx()
                val pathStepPx = 3.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width
                    val h = size.height
                    if (w <= 0 || h <= 0) return@awaitEachGesture
                    fun norm(p: Offset) = NormPoint((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
                    val start = down.position

                    // What does this touch grab? A handle of the selection, its outline, or nothing.
                    val selection = editor.selected
                    var handleIndex = -1
                    var handleGrabOffset = Offset.Zero
                    if (selection != null) {
                        var best = touchTolerance * 1.3f
                        SketchEngine.handles(selection).forEachIndexed { i, p ->
                            val hp = Offset(p.x * w, p.y * h)
                            val d = (hp - start).getDistance()
                            if (d <= best) {
                                best = d
                                handleIndex = i
                                handleGrabOffset = hp - start
                            }
                        }
                    }
                    val movesSelection = selection != null && handleIndex < 0 &&
                        SketchEngine.strokeDistancePx(selection, start.x, start.y, w, h) <= touchTolerance

                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop == null) {
                        // Released without dragging: select the shape under the finger (or clear).
                        if (currentEvent.changes.none { it.pressed }) {
                            val hit = SketchEngine.pick(editor.shapes, start.x, start.y, w, h, touchTolerance)
                            if (hit?.id != editor.selectedId && hit != null && haptics) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            editor.selectedId = hit?.id
                        }
                        return@awaitEachGesture
                    }

                    when {
                        selection != null && handleIndex >= 0 -> {
                            editor.checkpoint()
                            var shape: SketchShape = selection
                            fun moveTo(finger: Offset) {
                                val target = finger + handleGrabOffset
                                shape = SketchEngine.moveHandle(shape, handleIndex, norm(target))
                                editor.replace(shape)
                                loupeFocus = target
                            }
                            moveTo(slop.position)
                            drag(slop.id) { change ->
                                moveTo(change.position)
                                change.consume()
                            }
                        }

                        selection != null && movesSelection -> {
                            editor.checkpoint()
                            fun moveBy(finger: Offset) {
                                val delta = finger - start
                                editor.replace(SketchEngine.translate(selection, delta.x / w, delta.y / h))
                            }
                            moveBy(slop.position)
                            drag(slop.id) { change ->
                                moveBy(change.position)
                                change.consume()
                            }
                        }

                        else -> {
                            editor.selectedId = null
                            editor.beginDraft(norm(start))
                            editor.updateDraft(norm(slop.position), w, h, pathStepPx)
                            loupeFocus = slop.position
                            val finished = drag(slop.id) { change ->
                                editor.updateDraft(norm(change.position), w, h, pathStepPx)
                                loupeFocus = change.position
                                change.consume()
                            }
                            if (finished) {
                                val committed = editor.commitDraft(w, h, minShapePx)
                                if (committed != null) {
                                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestOnCommitted(committed)
                                } else {
                                    latestOnRejected()
                                }
                            } else {
                                editor.cancelDraft()
                            }
                        }
                    }
                    loupeFocus = null
                }
            }
    ) {
        drawImage(
            image = session.image,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        )
        drawSketchScene(
            shapes = editor.shapes,
            measurements = measurements,
            aspect = session.aspect,
            selectedId = editor.selectedId,
            highContrast = highContrast
        )
        // The draft changes every move, so it is measured here in the draw phase.
        editor.draft?.let { draft ->
            drawSketchShape(
                shape = draft,
                number = editor.shapes.size + 1,
                measurement = SketchEngine.measure(draft, session.scale, displayUnit, session.aspect),
                aspect = session.aspect,
                selected = false,
                highContrast = highContrast
            )
        }
        loupeFocus?.let { focus ->
            val color = SketchPalette[(editor.draft?.colorIndex ?: editor.selected?.colorIndex ?: editor.colorIndex) % SketchPalette.size]
            drawLoupe(session.image, focus, color)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Drawing (shared by the live canvas and the saved photo)
// -------------------------------------------------------------------------------------------

/** Draws all committed shapes, with handles on the selected one. */
internal fun DrawScope.drawSketchScene(
    shapes: List<SketchShape>,
    measurements: Map<Long, SketchMeasurement?>,
    aspect: Float,
    selectedId: Long?,
    highContrast: Boolean
) {
    shapes.forEachIndexed { index, shape ->
        drawSketchShape(
            shape = shape,
            number = index + 1,
            measurement = measurements[shape.id],
            aspect = aspect,
            selected = shape.id == selectedId,
            highContrast = highContrast
        )
    }
}

internal fun DrawScope.drawSketchShape(
    shape: SketchShape,
    number: Int,
    measurement: SketchMeasurement?,
    aspect: Float,
    selected: Boolean,
    highContrast: Boolean
) {
    val pts = shape.points
    if (pts.isEmpty()) return
    val color = SketchPalette[shape.colorIndex % SketchPalette.size]
    val core = (if (highContrast) 3.dp else 2.dp).toPx()
    val shadow = core + 3.dp.toPx()
    val shadowColor = Color(0x66000000)
    fun px(p: NormPoint) = Offset(p.x * size.width, p.y * size.height)

    fun strokePath(path: Path, fill: Boolean) {
        if (fill) drawPath(path, color.copy(alpha = 0.12f))
        if (selected) drawPath(path, color.copy(alpha = 0.35f), style = Stroke(width = core + 10.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawPath(path, shadowColor, style = Stroke(width = shadow, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(width = core, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }

    when (shape.tool) {
        SketchTool.LINE -> if (pts.size >= 2) {
            val a = px(pts[0])
            val b = px(pts[1])
            val path = Path().apply {
                moveTo(a.x, a.y)
                lineTo(b.x, b.y)
            }
            // Dimension-line end ticks, perpendicular to the line.
            val length = (b - a).getDistance()
            if (length > 1f) {
                val n = Offset(-(b.y - a.y) / length, (b.x - a.x) / length) * 9.dp.toPx()
                path.moveTo(a.x - n.x, a.y - n.y)
                path.lineTo(a.x + n.x, a.y + n.y)
                path.moveTo(b.x - n.x, b.y - n.y)
                path.lineTo(b.x + n.x, b.y + n.y)
            }
            strokePath(path, fill = false)
        }

        SketchTool.RECTANGLE -> if (pts.size >= 2) {
            val c = SketchEngine.boxCorners(pts[0], pts[1]).map(::px)
            val path = Path().apply {
                moveTo(c[0].x, c[0].y)
                for (i in 1 until 4) lineTo(c[i].x, c[i].y)
                close()
            }
            strokePath(path, fill = true)
        }

        SketchTool.CIRCLE -> if (pts.size >= 2) {
            val center = px(pts[0])
            val radius = (px(pts[1]) - center).getDistance()
            val path = Path().apply { addOval(Rect(center, radius)) }
            strokePath(path, fill = true)
            drawLine(
                color = color.copy(alpha = 0.85f),
                start = Offset(center.x - radius, center.y),
                end = Offset(center.x + radius, center.y),
                strokeWidth = core * 0.75f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            )
            drawCircle(color, radius = core * 1.4f, center = center)
        }

        SketchTool.FREEHAND -> if (pts.size >= 2) {
            val closed = SketchEngine.isClosedLoop(pts, aspect)
            val path = Path().apply {
                val first = px(pts[0])
                moveTo(first.x, first.y)
                for (i in 1 until pts.size) {
                    val p = px(pts[i])
                    lineTo(p.x, p.y)
                }
                if (closed) close()
            }
            strokePath(path, fill = closed)
        }
    }

    // Dimension labels, or a warning when the shape isn't over a measurable surface.
    if (measurement != null) {
        measurement.labels.forEach { label ->
            val a = px(label.from)
            val b = px(label.to)
            if (a == b) {
                drawWhiteDimensionPill(a + Offset(0f, -26.dp.toPx()), label.text, 0f, highContrast)
            } else {
                var angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                if (angle > 90f) angle -= 180f else if (angle < -90f) angle += 180f
                // Lift the pill off the line along its upward-facing normal.
                val length = (b - a).getDistance()
                var normal = Offset(-(b.y - a.y) / length, (b.x - a.x) / length)
                if (normal.y > 0f) normal = -normal
                val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f) + normal * 22.dp.toPx()
                drawWhiteDimensionPill(mid, label.text, angle, highContrast)
            }
        }
        measurement.centerLabel?.let { drawSurfaceCenterWatermark(px(it.at), it.text) }
    } else if (!SketchEngine.isTooSmall(shape, size.width.roundToInt(), size.height.roundToInt(), 8.dp.toPx())) {
        drawWhiteDimensionPill(px(pts[0]) + Offset(0f, -30.dp.toPx()), "No surface", 0f, highContrast)
    }

    drawNumberBadge(px(pts[0]), number, color)

    if (selected) {
        SketchEngine.handles(shape).forEach { h ->
            val c = px(h)
            drawCircle(shadowColor, radius = 11.dp.toPx(), center = c + Offset(0f, 1.dp.toPx()))
            drawCircle(Color.White, radius = 10.dp.toPx(), center = c)
            drawCircle(color, radius = 6.5.dp.toPx(), center = c)
        }
    }
}

/** Small numbered disc next to a shape's first point, matching captions and notes ("#2"). */
private fun DrawScope.drawNumberBadge(anchor: Offset, number: Int, color: Color) {
    val radius = 10.dp.toPx()
    val center = Offset(
        (anchor.x - radius * 1.6f).coerceIn(radius, size.width - radius),
        (anchor.y - radius * 1.6f).coerceIn(radius, size.height - radius)
    )
    drawCircle(Color(0x88000000), radius = radius + 1.5.dp.toPx(), center = center)
    drawCircle(color, radius = radius, center = center)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (color.luminance() > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        textSize = 12.dp.toPx()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val fm = paint.fontMetrics
    drawContext.canvas.nativeCanvas.drawText(number.toString(), center.x, center.y - (fm.ascent + fm.descent) / 2f, paint)
}

/**
 * Circular magnifier showing the frozen frame around [focus] at 2.5x with a crosshair on the
 * exact point, placed above the finger (below it near the top edge).
 */
private fun DrawScope.drawLoupe(image: ImageBitmap, focus: Offset, accent: Color) {
    val radius = 54.dp.toPx()
    val zoom = 2.5f
    val gap = 40.dp.toPx()
    val above = focus.y - gap - radius * 2f >= 0f
    val center = Offset(
        focus.x.coerceIn(radius, size.width - radius),
        if (above) focus.y - gap - radius else focus.y + gap + radius
    )

    // Source window in image pixels (the frame may differ slightly from the canvas size).
    val sx = image.width / size.width
    val sy = image.height / size.height
    val srcW = (radius * 2f / zoom * sx).roundToInt().coerceIn(1, image.width)
    val srcH = (radius * 2f / zoom * sy).roundToInt().coerceIn(1, image.height)
    val srcX = (focus.x * sx - srcW / 2f).roundToInt().coerceIn(0, image.width - srcW)
    val srcY = (focus.y * sy - srcH / 2f).roundToInt().coerceIn(0, image.height - srcH)

    val circle = Path().apply { addOval(Rect(center, radius)) }
    drawCircle(Color(0x66000000), radius = radius + 4.dp.toPx(), center = center + Offset(0f, 2.dp.toPx()))
    clipPath(circle) {
        drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = Size(radius * 2f, radius * 2f))
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
            dstSize = IntSize((radius * 2f).roundToInt(), (radius * 2f).roundToInt())
        )
    }
    drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 2.5.dp.toPx()))

    // Crosshair on the focus point (shifted when the window is clamped at an image edge).
    val target = center + Offset(
        (focus.x * sx - (srcX + srcW / 2f)) / sx * zoom,
        (focus.y * sy - (srcY + srcH / 2f)) / sy * zoom
    )
    val arm = 9.dp.toPx()
    val hole = 3.dp.toPx()
    val crossWidth = 2.dp.toPx()
    listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f)).forEach { dir ->
        drawLine(Color(0xAA000000), target + dir * hole, target + dir * (hole + arm), strokeWidth = crossWidth + 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent, target + dir * hole, target + dir * (hole + arm), strokeWidth = crossWidth, cap = StrokeCap.Round)
    }
}
