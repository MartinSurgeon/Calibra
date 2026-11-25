package com.example.ui.calibrate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ar.ArAvailability
import com.example.ar.ArCoreSupport
import com.example.ar.findActivity
import com.example.data.AppPreferences
import com.example.data.LevelReading
import com.example.domain.CalibrationPreset
import com.example.domain.LengthDisplayUnit
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCalibraTokens
import com.example.ui.theme.ThemeMode
import com.example.viewmodel.CalibraViewModel
import kotlin.math.abs

@Composable
fun CalibrateScreen(
    viewModel: CalibraViewModel,
    isExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val level by viewModel.levelReading.collectAsStateWithLifecycle()
    val measurements by viewModel.allMeasurements.collectAsStateWithLifecycle()
    val arAvailability by viewModel.arAvailability.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Pick up a completed Google Play Services for AR install when the user returns.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && arAvailability != ArAvailability.SUPPORTED) {
                viewModel.refreshArAvailability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val arEngineCard: @Composable () -> Unit = {
        ArEngineCard(
            availability = arAvailability,
            prefs = prefs,
            onPreferredToggle = viewModel::setArPreferred,
            onShowPlanesToggle = viewModel::setArShowPlanes,
            onDepthToggle = viewModel::setArDepthAssist,
            onInstall = {
                val activity = context.findActivity()
                if (activity != null && ArCoreSupport.requestInstall(activity, userRequested = true)) {
                    viewModel.refreshArAvailability()
                }
            }
        )
    }

    val pdfSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) viewModel.exportPdfToUri(context, uri) }

    val csvSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) viewModel.exportCsvToUri(context, uri) }

    val jsonBackupSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackupToUri(context, uri) }

    val jsonBackupOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importBackupFromUri(context, uri) }

    if (isExpanded) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                arEngineCard()
                ReferenceCalibratorCard(
                    prefs = prefs,
                    onApplyCalibration = viewModel::applyReferenceCalibration,
                    onWorkingDistanceChange = viewModel::updateWorkingDistance,
                    onTiltCompToggle = viewModel::setTiltCompensation
                )
                SpiritLevelCalibrationCard(level = level)
            }

            Column(
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AppearanceAccessibilityCard(
                    prefs = prefs,
                    onThemeSelected = viewModel::setThemeMode,
                    onHighContrastToggle = viewModel::setHighContrast,
                    onUnitSelected = viewModel::setDisplayUnit,
                    onHapticToggle = viewModel::setHapticFeedback
                )
                OfflineVaultBackupCard(
                    recordCount = measurements.size,
                    onExportPdf = { pdfSaver.launch("calibra_report.pdf") },
                    onExportCsv = { csvSaver.launch("calibra_measurements.csv") },
                    onBackupJson = { jsonBackupSaver.launch("calibra_vault_backup.json") },
                    onRestoreJson = { jsonBackupOpener.launch(arrayOf("application/json", "text/plain")) }
                )
                TutorialLauncherCard(onLaunchTutorial = viewModel::openTutorial)
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            arEngineCard()
            ReferenceCalibratorCard(
                prefs = prefs,
                onApplyCalibration = viewModel::applyReferenceCalibration,
                onWorkingDistanceChange = viewModel::updateWorkingDistance,
                onTiltCompToggle = viewModel::setTiltCompensation
            )
            SpiritLevelCalibrationCard(level = level)
            AppearanceAccessibilityCard(
                prefs = prefs,
                onThemeSelected = viewModel::setThemeMode,
                onHighContrastToggle = viewModel::setHighContrast,
                onUnitSelected = viewModel::setDisplayUnit,
                onHapticToggle = viewModel::setHapticFeedback
            )
            OfflineVaultBackupCard(
                recordCount = measurements.size,
                onExportPdf = { pdfSaver.launch("calibra_report.pdf") },
                onExportCsv = { csvSaver.launch("calibra_measurements.csv") },
                onBackupJson = { jsonBackupSaver.launch("calibra_vault_backup.json") },
                onRestoreJson = { jsonBackupOpener.launch(arrayOf("application/json", "text/plain")) }
            )
            TutorialLauncherCard(onLaunchTutorial = viewModel::openTutorial)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReferenceCalibratorCard(
    prefs: AppPreferences,
    onApplyCalibration: (CalibrationPreset, Double, Float, Float) -> Unit,
    onWorkingDistanceChange: (Float) -> Unit,
    onTiltCompToggle: (Boolean) -> Unit
) {
    val tokens = LocalCalibraTokens.current
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(CalibrationPreset.CREDIT_CARD) }
    var customCmText by remember { mutableStateOf("10.0") }
    var caliperSpan by remember { mutableFloatStateOf(0.45f) }
    var workingDistance by remember(prefs.calibration.workingDistanceCm) {
        mutableFloatStateOf(prefs.calibration.workingDistanceCm)
    }

    val knownCm = if (selectedPreset == CalibrationPreset.CUSTOM) {
        (customCmText.toDoubleOrNull() ?: 10.0).coerceIn(0.5, 500.0)
    } else {
        selectedPreset.knownCm
    }

    // Physical DPI screen ruler estimate so user can verify real-world mm on glass
    val xdpi = context.resources.displayMetrics.xdpi.takeIf { it > 60f } ?: 320f
    val screenWidthCm = (context.resources.displayMetrics.widthPixels / xdpi) * 2.54f

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.calibrate_reference_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "${UnitConverterEngine.formatValue(prefs.calibration.cmPerNormWidth.toDouble(), 1)} cm/view",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Used by the calibrated 2D camera mode only — ARCore mode measures at true world scale without calibration.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Text(
                text = "1. Pick a known physical reference object and align the Vernier caliper jaws to match its apparent width in your camera frame.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CalibrationPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("calibration_preset_${preset.name.lowercase()}")
                    )
                }
            }

            if (selectedPreset == CalibrationPreset.CUSTOM) {
                OutlinedTextField(
                    value = customCmText,
                    onValueChange = { customCmText = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                    label = { Text("Known Object Length (cm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "${selectedPreset.label}: ${selectedPreset.description}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Visual Vernier Caliper Gauge
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0B132B),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    val leftX = size.width * (0.5f - caliperSpan * 0.45f)
                    val rightX = size.width * (0.5f + caliperSpan * 0.45f)
                    val midY = size.height * 0.56f

                    // Ruler tick marks
                    val ticks = 30
                    for (i in 0..ticks) {
                        val tx = size.width * (i.toFloat() / ticks)
                        val tickH = if (i % 5 == 0) 16f else 8f
                        drawLine(Color(0x6694A3B8), Offset(tx, size.height), Offset(tx, size.height - tickH), 1.5f)
                    }

                    // Active caliper span
                    drawLine(tokens.laserPrimary, Offset(leftX, midY), Offset(rightX, midY), 5f, StrokeCap.Round)
                    // Left & Right jaws
                    drawLine(tokens.laserSecondary, Offset(leftX, 6f), Offset(leftX, size.height - 10f), 4.5f, StrokeCap.Round)
                    drawLine(tokens.laserSecondary, Offset(rightX, 6f), Offset(rightX, size.height - 10f), 4.5f, StrokeCap.Round)
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Caliper Span in Viewfinder", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "${(caliperSpan * 100).toInt()}% of frame • Physical ~${UnitConverterEngine.formatValue((caliperSpan * screenWidthCm).toDouble(), 1)} cm on glass",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = caliperSpan,
                    onValueChange = { caliperSpan = it },
                    valueRange = 0.12f..0.92f,
                    modifier = Modifier.testTag("calibration_span_slider")
                )
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Camera Working Distance", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "${workingDistance.toInt()} cm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = workingDistance,
                    onValueChange = { workingDistance = it },
                    onValueChangeFinished = { onWorkingDistanceChange(workingDistance) },
                    valueRange = 15f..300f,
                    modifier = Modifier.testTag("working_distance_slider")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Optical Tilt Compensation", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Corrects foreshortening using device gravity sensors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.calibration.tiltCompensationEnabled,
                    onCheckedChange = onTiltCompToggle,
                    modifier = Modifier.testTag("tilt_compensation_switch")
                )
            }

            Button(
                onClick = { onApplyCalibration(selectedPreset, knownCm, caliperSpan, workingDistance) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("apply_calibration_button")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${stringResource(R.string.calibrate_apply)} (${UnitConverterEngine.formatValue(knownCm, 2)} cm)")
            }
        }
    }
}

@Composable
private fun ArEngineCard(
    availability: ArAvailability,
    prefs: AppPreferences,
    onPreferredToggle: (Boolean) -> Unit,
    onShowPlanesToggle: (Boolean) -> Unit,
    onDepthToggle: (Boolean) -> Unit,
    onInstall: () -> Unit
) {
    val tokens = LocalCalibraTokens.current
    val statusColor = when (availability) {
        ArAvailability.SUPPORTED -> tokens.laserSecondary
        ArAvailability.NEEDS_INSTALL, ArAvailability.CHECKING -> tokens.laserWarning
        ArAvailability.UNSUPPORTED -> MaterialTheme.colorScheme.error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ar_engine_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("AR World Tracking (ARCore)", style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = availability.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("ar_availability_label")
                )
            }
            Text(
                text = "Detects floors, walls and tables, then anchors your points in real 3D space — " +
                    "measurements stay put as you walk around and need no reference calibration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (availability == ArAvailability.NEEDS_INSTALL) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calibrate_install_arcore_button")
                ) {
                    Text("Install Google Play Services for AR")
                }
            }
            SettingSwitchRow(
                title = "Use ARCore when available",
                subtitle = "Off = always use the calibrated 2D camera mode",
                checked = prefs.arPreferred,
                enabled = availability != ArAvailability.UNSUPPORTED,
                onCheckedChange = onPreferredToggle,
                testTag = "ar_preferred_switch"
            )
            SettingSwitchRow(
                title = "Show detected surfaces",
                subtitle = "Dot grid over floors, walls and tabletops",
                checked = prefs.arShowPlanes,
                enabled = availability.isUsable,
                onCheckedChange = onShowPlanesToggle,
                testTag = "ar_planes_switch"
            )
            SettingSwitchRow(
                title = "Depth-assisted placement",
                subtitle = "Place points on curved or uneven surfaces (devices with Depth API)",
                checked = prefs.arDepthAssist,
                enabled = availability.isUsable,
                onCheckedChange = onDepthToggle,
                testTag = "ar_depth_switch"
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SpiritLevelCalibrationCard(level: LevelReading) {
    val tokens = LocalCalibraTokens.current
    val levelColor = if (level.isLevel) tokens.laserSecondary else tokens.laserWarning

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular bullseye bubble level
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color(0xFF0B132B)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawCircle(Color(0x5538BDF8), radius = size.minDimension * 0.42f, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    drawCircle(Color(0x8838BDF8), radius = size.minDimension * 0.16f, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    val bx = (cx + (level.rollDeg / 45f).coerceIn(-1f, 1f) * size.minDimension * 0.34f)
                    val by = (cy + (level.pitchDeg / 45f).coerceIn(-1f, 1f) * size.minDimension * 0.34f)
                    drawCircle(levelColor, radius = 10f, center = Offset(bx, by))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Explore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Live Sensor Spirit Level", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = if (level.hasSensor) {
                        "Pitch ${UnitConverterEngine.formatValue(level.pitchDeg.toDouble(), 1)}°  •  Roll ${UnitConverterEngine.formatValue(level.rollDeg.toDouble(), 1)}°"
                    } else {
                        "Gravity sensor unavailable on this device"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (level.isLevel) "Device plane aligned for optimal optical accuracy."
                    else "Tilt detected (${UnitConverterEngine.formatValue(abs(level.planeDeviationDeg).toDouble(), 1)}° off plane).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppearanceAccessibilityCard(
    prefs: AppPreferences,
    onThemeSelected: (ThemeMode) -> Unit,
    onHighContrastToggle: (Boolean) -> Unit,
    onUnitSelected: (LengthDisplayUnit) -> Unit,
    onHapticToggle: (Boolean) -> Unit
) {
    val tokens = LocalCalibraTokens.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.calibrate_appearance_section), style = MaterialTheme.typography.titleMedium)
            }

            // Theme mode chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Theme Mode (Low-Light Support)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.themeMode == mode,
                            onClick = { onThemeSelected(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_mode_${mode.name.lowercase()}")
                        )
                    }
                }
            }

            // High Contrast WCAG AAA Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Contrast, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("High-Contrast Mode (WCAG AAA)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Maximizes text contrast, card borders, and AR laser legibility",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = prefs.highContrast,
                    onCheckedChange = onHighContrastToggle,
                    modifier = Modifier.testTag("high_contrast_switch")
                )
            }

            // Default length unit selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Default Measurement Unit", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LengthDisplayUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = prefs.displayUnit == unit,
                            onClick = { onUnitSelected(unit) },
                            label = { Text(unit.symbol) }
                        )
                    }
                }
            }

            // Haptics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tactile Anchor Feedback", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Subtle vibration when grabbing handles or placing vertices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.hapticFeedback,
                    onCheckedChange = onHapticToggle
                )
            }
        }
    }
}

@Composable
private fun OfflineVaultBackupCard(
    recordCount: Int,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
    onBackupJson: () -> Unit,
    onRestoreJson: () -> Unit
) {
    val tokens = LocalCalibraTokens.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.calibrate_backup_section), style = MaterialTheme.typography.titleMedium)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "$recordCount saved",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onExportPdf, enabled = recordCount > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PDF")
                }
                FilledTonalButton(onClick = onExportCsv, enabled = recordCount > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CSV")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBackupJson, enabled = recordCount > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Backup")
                }
                OutlinedButton(onClick = onRestoreJson, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.calibrate_cloud_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorialLauncherCard(onLaunchTutorial: () -> Unit) {
    val tokens = LocalCalibraTokens.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.secondary),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Interactive Beginner Guide", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Step-by-step hands-on tour of AR outlining, calibration & exports",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onLaunchTutorial,
                modifier = Modifier.testTag("launch_tutorial_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Start")
            }
        }
    }
}
