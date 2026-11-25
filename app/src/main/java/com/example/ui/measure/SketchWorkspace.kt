package com.example.ui.measure

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.LengthDisplayUnit
import com.example.domain.MeasurementSource
import com.example.domain.SketchEngine
import com.example.domain.SketchMeasurement
import com.example.domain.SketchShape
import com.example.domain.SketchTool
import com.example.ui.theme.JetBrainsMonoFontFamily

/**
 * Full-viewport sketch mode: the frozen camera frame with the drawing canvas, a top bar
 * (exit, engine pill, save), contextual guidance, a live readout of the focused shape and the
 * tool dock. Measurements of committed shapes are computed here so only this subtree recomposes
 * while shapes are edited.
 */
@Composable
internal fun SketchWorkspace(
    session: SketchSession,
    editor: SketchEditorState,
    displayUnit: LengthDisplayUnit,
    highContrast: Boolean,
    hapticEnabled: Boolean,
    saving: Boolean,
    padStatusBar: Boolean,
    onExit: () -> Unit,
    onSave: (Map<Long, SketchMeasurement?>) -> Unit,
    onCycleUnit: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val measurements = remember(editor.shapes, session, displayUnit) {
        editor.shapes.associate { it.id to SketchEngine.measure(it, session.scale, displayUnit, session.aspect) }
    }
    val drawing by remember { derivedStateOf { editor.draft != null } }
    val isAr = session.source == MeasurementSource.ARCORE

    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("sketch_workspace")
    ) {
        SketchCanvas(
            session = session,
            editor = editor,
            measurements = measurements,
            displayUnit = displayUnit,
            highContrast = highContrast,
            hapticEnabled = hapticEnabled,
            onShapeCommitted = {},
            onDraftRejected = {
                if (editor.isFull) onMessage("The sketch is full — delete a shape to draw another")
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
        )

        // Top bar + guidance
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .then(if (padStatusBar) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onExit, modifier = Modifier.testTag("sketch_exit_button")) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(" Exit", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0x8A111827),
                    border = BorderStroke(1.dp, if (session.measurable) Color(0x88FBBF24) else Color(0x88F87171))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Draw, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(14.dp))
                        Text(
                            text = if (isAr) "SKETCH • AR SURFACE" else "SKETCH • CALIBRATED",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                TextButton(
                    onClick = { onSave(measurements) },
                    enabled = !saving,
                    modifier = Modifier.testTag("sketch_save_button")
                ) {
                    if (saving) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Save", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            val selected = editor.selected
            val hint = when {
                !session.measurable -> "AR hadn't found surfaces in this view — exit, scan slowly, then sketch again"
                drawing -> null
                selected != null && selected.tool == SketchTool.FREEHAND -> "Drag the path to move it"
                selected != null -> "Drag the dots to adjust • drag the outline to move it"
                editor.shapes.isEmpty() -> editor.tool.hint
                else -> "${editor.tool.hint} • tap a shape to select it"
            }
            if (hint != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (session.measurable) Color(0xCC111827) else Color(0xD9B45309),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .testTag("sketch_hint_chip")
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Readout + tool dock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SketchReadout(session = session, editor = editor, measurements = measurements, displayUnit = displayUnit)
            SketchToolDock(editor = editor, displayUnit = displayUnit, onCycleUnit = onCycleUnit)
        }
    }
}

/** Live value of the shape being drawn, else the selected one, else the latest. */
@Composable
private fun SketchReadout(
    session: SketchSession,
    editor: SketchEditorState,
    measurements: Map<Long, SketchMeasurement?>,
    displayUnit: LengthDisplayUnit
) {
    val draft = editor.draft
    val focus = draft ?: editor.selected ?: editor.shapes.lastOrNull() ?: return
    val measurement = if (focus === draft) {
        SketchEngine.measure(focus, session.scale, displayUnit, session.aspect)
    } else {
        measurements[focus.id]
    }
    val accent = SketchPalette[focus.colorIndex % SketchPalette.size]

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xE6111827),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sketch_readout")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${editor.numberOf(focus)}",
                    color = if (accent.luminance() > 0.5f) Color(0xFF111827) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = if (measurement != null) {
                        "${focus.tool.label.uppercase()} • ${measurement.result.primaryLabel.uppercase()}"
                    } else {
                        focus.tool.label.uppercase()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = measurement?.result?.formattedPrimary ?: "No surface data",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.testTag("sketch_primary_readout")
                )
                Text(
                    text = when {
                        measurement != null -> measurement.result.secondarySummary
                        session.source == MeasurementSource.ARCORE -> "Part of this shape isn't over a surface ARCore detected"
                        else -> "Draw a larger shape to measure it"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFCBD5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SketchToolDock(
    editor: SketchEditorState,
    displayUnit: LengthDisplayUnit,
    onCycleUnit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xE6111827),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SketchTool.entries.forEach { tool ->
                    DockButton(
                        label = tool.label,
                        selected = editor.tool == tool,
                        onClick = { editor.tool = tool },
                        testTag = "sketch_tool_${tool.name.lowercase()}"
                    ) { tint -> Icon(toolIcon(tool), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
                }
                DockButton(
                    label = "Color",
                    selected = false,
                    onClick = editor::cycleColor,
                    testTag = "sketch_color_button"
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(SketchPalette[editor.colorIndex])
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockButton("Undo", selected = false, enabled = editor.canUndo, onClick = editor::undo, testTag = "sketch_undo_button") { tint ->
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                DockButton("Delete", selected = false, enabled = editor.selected != null, onClick = editor::deleteSelected, testTag = "sketch_delete_button") { tint ->
                    Icon(Icons.Default.Delete, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                DockButton("Clear", selected = false, enabled = editor.shapes.isNotEmpty(), onClick = editor::clear, testTag = "sketch_clear_button") { tint ->
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                DockButton("Unit", selected = false, onClick = onCycleUnit, testTag = "sketch_unit_button") { tint ->
                    Text(displayUnit.symbol, color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun DockButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true,
    icon: @Composable (tint: Color) -> Unit
) {
    val tint = when {
        selected -> Color(0xFF111827)
        enabled -> Color.White
        else -> Color(0x55FFFFFF)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color.White else Color.Transparent,
        modifier = Modifier.testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon(tint) }
            Text(text = label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun toolIcon(tool: SketchTool): ImageVector = when (tool) {
    SketchTool.LINE -> Icons.Default.HorizontalRule
    SketchTool.RECTANGLE -> Icons.Default.CropSquare
    SketchTool.CIRCLE -> Icons.Default.RadioButtonUnchecked
    SketchTool.FREEHAND -> Icons.Default.Gesture
}

/**
 * The frozen frame with every shape and its labels burned in (no selection handles), at the
 * frame's own resolution. Never draws onto [SketchSession.bitmap] itself.
 */
internal fun renderSketchPhoto(
    session: SketchSession,
    shapes: List<SketchShape>,
    measurements: Map<Long, SketchMeasurement?>,
    highContrast: Boolean,
    density: Density
): Bitmap = renderOverlayOnto(session.bitmap.copy(Bitmap.Config.ARGB_8888, true), density) {
    drawSketchScene(
        shapes = shapes,
        measurements = measurements,
        aspect = session.aspect,
        selectedId = null,
        highContrast = highContrast
    )
}
