package com.example.ui.tutorial

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.domain.ShapeMode
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCalibraTokens

private data class TutorialStep(
    val title: String,
    val subtitle: String,
    val description: String,
    val tip: String,
    val icon: ImageVector
)

private val tutorialSteps = listOf(
    TutorialStep(
        title = "True-Scale AR World Tracking",
        subtitle = "Step 1 of 4 • Visual Scanning",
        description = "On ARCore devices, sweep your phone slowly until surfaces light up with dots, aim the reticle and tap + to drop anchored points — lines, areas, circles, angles, or a full 3D box with volume. Points stay locked in place as you walk around. Other devices use live edge outlining with calibration.",
        tip = "Lines snap to LEVEL or PLUMB automatically, and aiming at an existing point snaps onto it. Tap PHOTO to save a picture with the dimensions burned in.",
        icon = Icons.Default.AutoAwesome
    ),
    TutorialStep(
        title = "Precision Reference Calibration",
        subtitle = "Step 2 of 4 • Optical Accuracy",
        description = "For millimeter-grade accuracy, calibrate using a standard Credit Card (85.6 mm), Coin, or A4 sheet. Hold your device level using the live green Spirit Level HUD.",
        tip = "Drag the interactive practice slider below to see how reference calibration scales dimensions.",
        icon = Icons.Default.Tune
    ),
    TutorialStep(
        title = "Manual Fine-Tuning & Vertices",
        subtitle = "Step 3 of 4 • Full Control",
        description = "Tap the Lock button to freeze a live outline, then drag any glowing handle to snap cleanly onto corners. In Polygon mode, tap anywhere on the viewport to drop extra vertices.",
        tip = "Switch effortlessly between mm, cm, m, in, and ft directly on the readout bar.",
        icon = Icons.Default.Straighten
    ),
    TutorialStep(
        title = "Universal Units, Offline Vault & Exports",
        subtitle = "Step 4 of 4 • Backup & Reports",
        description = "Convert across 9 measurement categories, browse your offline SQLite history with vector blueprints, and export polished PDF reports, CSV spreadsheets, or JSON backups for desktop.",
        tip = "Toggle Dark Mode or WCAG AAA High-Contrast anytime from the top bar for low-light visibility.",
        icon = Icons.Default.PictureAsPdf
    )
)

@Composable
fun TutorialOverlayDialog(
    onDismiss: () -> Unit,
    onSelectShapeAndFinish: (ShapeMode) -> Unit
) {
    val tokens = LocalCalibraTokens.current
    var currentStep by remember { mutableIntStateOf(0) }
    var practiceShape by remember { mutableStateOf(ShapeMode.SNAP_3D) }
    var practiceSpan by remember { mutableFloatStateOf(0.42f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
            tonalElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag("tutorial_overlay_dialog")
        ) {
            val safeStep = currentStep.coerceIn(0, tutorialSteps.lastIndex)
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Step Badge & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                imageVector = tutorialSteps[safeStep].icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(8.dp).size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = tutorialSteps[safeStep].subtitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("tutorial_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close tutorial")
                    }
                }

                // Hero illustration on Step 0
                if (safeStep == 0) {
                    Image(
                        painter = painterResource(id = R.drawable.img_tutorial_ar_measure_1795439826668),
                        contentDescription = "Augmented reality shape outlining blueprint illustration",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(156.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }

                // Animated step body
                AnimatedContent(targetState = safeStep, label = "tutorial_step") { stepIndex ->
                    val step = tutorialSteps[stepIndex.coerceIn(0, tutorialSteps.lastIndex)]
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = step.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Interactive Try-It Sandbox right inside Steps 0 and 1!
                        when (stepIndex) {
                            0 -> {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Interactive Preview — Choose a Shape:", style = MaterialTheme.typography.labelLarge)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                ShapeMode.SNAP_3D,
                                                ShapeMode.RECTANGLE,
                                                ShapeMode.LINE,
                                                ShapeMode.CIRCLE,
                                                ShapeMode.ANGLE
                                            ).forEach { m ->
                                                FilterChip(
                                                    selected = practiceShape == m,
                                                    onClick = { practiceShape = m },
                                                    label = { Text(m.label) }
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Computes: ${practiceShape.shortHint}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            1 -> {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val simScale = 8.56 / practiceSpan.coerceAtLeast(0.15f)
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Practice: Credit Card (8.56 cm)", style = MaterialTheme.typography.labelLarge)
                                            Text(
                                                text = "View = ${UnitConverterEngine.formatValue(simScale, 1)} cm",
                                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = practiceSpan,
                                            onValueChange = { practiceSpan = it },
                                            valueRange = 0.20f..0.80f
                                        )
                                    }
                                }
                            }
                            else -> {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = step.tip,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Progress dots
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tutorialSteps.indices.forEach { idx ->
                        val active = idx == safeStep
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(8.dp)
                                .width(if (active) 24.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                }

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (safeStep == 0) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("tutorial_skip_button")
                        ) {
                            Text(stringResource(R.string.tutorial_skip))
                        }
                    } else {
                        OutlinedButton(onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) }) {
                            Text(stringResource(R.string.tutorial_back))
                        }
                    }

                    if (safeStep < tutorialSteps.lastIndex) {
                        Button(
                            onClick = { currentStep = (currentStep + 1).coerceAtMost(tutorialSteps.lastIndex) },
                            modifier = Modifier.testTag("tutorial_next_button")
                        ) {
                            Text(stringResource(R.string.tutorial_next))
                        }
                    } else {
                        Button(
                            onClick = { onSelectShapeAndFinish(practiceShape) },
                            modifier = Modifier.testTag("tutorial_finish_button")
                        ) {
                            Text(stringResource(R.string.tutorial_finish))
                        }
                    }
                }
            }
        }
    }
}
