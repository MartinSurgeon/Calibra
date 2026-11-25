package com.example.ui.measure

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ar.ArAvailability
import com.example.ar.ArCoreSupport
import com.example.ar.ArEvent
import com.example.ar.ArSessionController
import com.example.ar.ArSessionStatus
import com.example.ar.ArSurfaceKind
import com.example.ar.ArTrackingQuality
import com.example.ar.findActivity
import com.example.domain.CalibratedSketchScale
import com.example.domain.GeometryEngine
import com.example.domain.LengthDisplayUnit
import com.example.domain.MeasurementSource
import com.example.domain.NormPoint
import com.example.domain.ShapeMeasurementResult
import com.example.domain.ShapeMode
import com.example.domain.SketchEngine
import com.example.domain.SketchMeasurement
import com.example.domain.SpatialMeasurementEngine
import com.example.domain.UnitConverterEngine
import com.example.ui.history.SnapshotImage
import com.example.ui.theme.ArNodeBlue
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCalibraTokens
import com.example.viewmodel.CalibraViewModel
import com.example.viewmodel.SketchSaveRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MeasureScreen(
    viewModel: CalibraViewModel,
    arController: ArSessionController,
    isExpanded: Boolean,
    onNavigateToCalibrate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val measureState by viewModel.measureState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val levelReading by viewModel.levelReading.collectAsStateWithLifecycle()
    val result by viewModel.liveMeasurementResult.collectAsStateWithLifecycle()
    val arAvailability by viewModel.arAvailability.collectAsStateWithLifecycle()
    val arSnapshot by viewModel.arMeasurement.collectAsStateWithLifecycle()
    val pendingSnapshot by viewModel.pendingSnapshotPath.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showPermissionPrompt by remember { mutableStateOf(!hasCameraPermission) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var arFailure by rememberSaveable { mutableStateOf<String?>(null) }
    var installRequested by rememberSaveable { mutableStateOf(false) }
    var installPromptDismissed by rememberSaveable { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val captureHandle = remember { ViewportCaptureHandle() }
    val density = LocalDensity.current

    // Sketch mode: a frozen frame with a drawing overlay (null = live camera).
    var sketchSession by remember { mutableStateOf<SketchSession?>(null) }
    val sketchEditor = remember { SketchEditorState() }
    var freezingSketch by remember { mutableStateOf(false) }
    var savingSketch by remember { mutableStateOf(false) }
    var confirmSketchExit by remember { mutableStateOf(false) }
    var pendingSketchSave by remember { mutableStateOf<SketchSaveRequest?>(null) }
    var pendingSketchNotes by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        showPermissionPrompt = false
        if (granted) {
            viewModel.setCameraEnabled(true)
        } else {
            viewModel.setCameraEnabled(false)
            viewModel.notifyUser("Spatial Studio Mode active. Tap Camera icon anytime for live AR.")
        }
    }

    val effectiveCameraActive = hasCameraPermission && measureState.cameraEnabled
    val arEngineActive = effectiveCameraActive &&
        prefs.arPreferred &&
        arAvailability.isUsable &&
        arFailure == null

    // --- AR session wiring --------------------------------------------------------------------

    val arFrame = arController.frameState.collectAsState()
    val arStatus by remember { derivedStateOf { arFrame.value.status } }
    val arHint by remember { derivedStateOf { arFrame.value.hint } }
    val arTracking by remember { derivedStateOf { arFrame.value.tracking } }
    val arReticleKind by remember { derivedStateOf { arFrame.value.reticle?.kind } }
    val arDepthActive by remember { derivedStateOf { arFrame.value.depthActive } }
    val arDistanceCm by remember {
        derivedStateOf { arFrame.value.reticle?.distanceM?.let { (it * 100).roundToInt() } }
    }
    val latestHaptics by rememberUpdatedState(prefs.hapticFeedback)

    LaunchedEffect(arEngineActive) {
        if (arEngineActive) {
            arController.measurement.collect { viewModel.updateArMeasurement(it) }
        } else {
            viewModel.updateArMeasurement(null)
        }
    }
    LaunchedEffect(measureState.flashlightEnabled) { arController.setTorchEnabled(measureState.flashlightEnabled) }
    LaunchedEffect(prefs.arDepthAssist) { arController.setDepthEnabled(prefs.arDepthAssist) }
    LaunchedEffect(arController) {
        arController.events.collect { event ->
            when (event) {
                is ArEvent.PointPlaced ->
                    if (latestHaptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                is ArEvent.ShapeCompleted ->
                    if (latestHaptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                is ArEvent.Message -> viewModel.notifyUser(event.text)
            }
        }
    }
    LaunchedEffect(arStatus, arEngineActive) {
        val status = arStatus
        if (arEngineActive && status is ArSessionStatus.Failed && status.fatal) {
            arFailure = status.message
            viewModel.notifyUser("${status.message}. Switched to calibrated camera mode.")
        }
    }

    // Returning from the Play Store install flow or the permission settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (installRequested) {
                    installRequested = false
                    activity?.let { ArCoreSupport.requestInstall(it, userRequested = false) }
                }
                if (arAvailability != ArAvailability.SUPPORTED) viewModel.refreshArAvailability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Actions ------------------------------------------------------------------------------

    fun cycleUnit() {
        val list = LengthDisplayUnit.entries
        val next = list[(list.indexOf(prefs.displayUnit) + 1) % list.size]
        viewModel.setDisplayUnit(next)
        viewModel.notifyUser("Display unit: ${next.symbol}")
    }

    fun requestArInstall() {
        val host = activity ?: return
        if (ArCoreSupport.requestInstall(host, userRequested = true)) {
            viewModel.refreshArAvailability()
        } else {
            installRequested = true
        }
    }

    fun toggleEngine() {
        when {
            arEngineActive -> {
                viewModel.setArPreferred(false)
                viewModel.notifyUser("Calibrated 2D camera mode")
            }
            !hasCameraPermission -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            arAvailability == ArAvailability.NEEDS_INSTALL -> requestArInstall()
            arAvailability == ArAvailability.UNSUPPORTED ->
                viewModel.notifyUser("ARCore isn't supported on this device — using calibrated mode")
            arAvailability == ArAvailability.CHECKING -> viewModel.notifyUser("Still checking ARCore support…")
            else -> {
                arFailure = null
                arController.acknowledgeFailure()
                viewModel.setCameraEnabled(true)
                viewModel.setArPreferred(true)
                viewModel.notifyUser("ARCore world tracking — no calibration needed")
            }
        }
    }

    fun onPrimaryAction() {
        when {
            arEngineActive -> arController.placePoint() // haptics fire on the PointPlaced event
            measureState.shapeMode == ShapeMode.POLYGON -> {
                if (prefs.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // Drop a new vertex slightly offset from center
                val offsetIndex = measureState.points.size
                val angle = offsetIndex * 0.9f
                viewModel.addPolygonVertex(
                    NormPoint(
                        x = (0.50f + kotlin.math.cos(angle) * 0.16f).coerceIn(0.15f, 0.85f),
                        y = (0.42f + kotlin.math.sin(angle) * 0.14f).coerceIn(0.18f, 0.68f)
                    )
                )
                viewModel.notifyUser("Added polygon vertex #${measureState.points.size + 1}")
            }
            else -> {
                if (prefs.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showSaveDialog = true
            }
        }
    }

    fun onCapturePhoto() {
        if (prefs.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (capturing) return
        capturing = true
        val r = result
        val source = if (arEngineActive) MeasurementSource.ARCORE else MeasurementSource.CALIBRATED
        scope.launch {
            val shot = captureHandle.capture()
            if (shot != null) {
                val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date())
                val stamped = withContext(Dispatchers.Default) {
                    stampMeasurementCaption(
                        snapshot = shot,
                        headline = "${r.primaryLabel}  ${r.formattedPrimary}",
                        detail = r.secondaryMetrics.joinToString("   ") { "${it.first} ${it.second}" },
                        footer = "CALIBRA  •  ${r.shapeMode.label.uppercase()}  •  ${source.label}  •  $stamp"
                    )
                }
                viewModel.storePendingSnapshot(stamped)
            } else {
                viewModel.notifyUser("Couldn't capture the camera view — saving without a photo")
            }
            capturing = false
            showSaveDialog = true
        }
    }

    /** Freezes what the camera shows and opens the sketch overlay on it. */
    fun startSketch() {
        if (freezingSketch || sketchSession != null) return
        if (prefs.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        freezingSketch = true
        val useAr = arEngineActive
        val calibration = prefs.calibration
        val tilt = if (levelReading.hasSensor) levelReading.planeDeviationDeg else 0f
        scope.launch {
            val session = if (useAr) {
                arController.freezeFrame()?.let { frozen ->
                    SketchSession(
                        bitmap = frozen.bitmap,
                        scale = frozen.surfaceMap,
                        source = MeasurementSource.ARCORE,
                        measurable = frozen.tracking && frozen.surfaceMap.hasSurfaces
                    )
                }
            } else {
                captureHandle.captureFrame()?.let { frame ->
                    SketchSession(
                        bitmap = frame,
                        scale = CalibratedSketchScale(
                            cmPerNormWidth = GeometryEngine.effectiveCmPerWidth(calibration, tilt),
                            aspectHeightOverWidth = frame.height.toFloat() / frame.width
                        ),
                        source = MeasurementSource.CALIBRATED,
                        measurable = true
                    )
                }
            }
            freezingSketch = false
            if (session == null) {
                viewModel.notifyUser("Couldn't freeze the camera view — try again")
                return@launch
            }
            sketchEditor.reset()
            sketchSession = session
        }
    }

    fun exitSketch(discardConfirmed: Boolean = false) {
        if (!discardConfirmed && sketchEditor.shapes.isNotEmpty()) {
            confirmSketchExit = true
            return
        }
        sketchSession = null
        sketchEditor.reset()
    }

    /** Burns the sketch into the frozen frame and opens the save dialog for the focused shape. */
    fun saveSketch(measurements: Map<Long, SketchMeasurement?>) {
        val session = sketchSession ?: return
        val shapes = sketchEditor.shapes
        val focus = sketchEditor.selected ?: shapes.lastOrNull()
        if (focus == null) {
            viewModel.notifyUser("Draw a shape to measure first")
            return
        }
        val number = sketchEditor.numberOf(focus)
        val measured = measurements[focus.id]
        if (measured == null) {
            viewModel.notifyUser("Shape #$number isn't over a detected surface — select a measured shape")
            return
        }
        if (savingSketch) return
        savingSketch = true
        val highContrast = prefs.highContrast
        val summaries = shapes.mapIndexed { i, s ->
            "#${i + 1} ${measurements[s.id]?.summary ?: "${s.tool.label} (no surface)"}"
        }
        val r = measured.result
        scope.launch {
            val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date())
            val photo = withContext(Dispatchers.Default) {
                stampMeasurementCaption(
                    snapshot = renderSketchPhoto(session, shapes, measurements, highContrast, density),
                    headline = "#$number ${r.primaryLabel}  ${r.formattedPrimary}",
                    detail = summaries.joinToString("   "),
                    footer = "CALIBRA  •  SKETCH  •  ${session.source.label}  •  $stamp"
                )
            }
            viewModel.storePendingSnapshot(photo)
            pendingSketchSave = SketchSaveRequest(
                result = r,
                blueprint = SketchEngine.blueprintPoints(focus, session.aspect),
                worldPoints = measured.worldPoints,
                source = session.source
            )
            pendingSketchNotes = if (shapes.size > 1) summaries.joinToString("\n") else ""
            savingSketch = false
            showSaveDialog = true
        }
    }

    BackHandler(enabled = sketchSession != null) { exitSketch() }

    val placedCount = arSnapshot?.takeIf { it.mode == measureState.shapeMode && !it.isComplete }?.placedCount ?: 0
    val arShapeComplete = arSnapshot?.let { it.mode == measureState.shapeMode && it.isComplete } == true
    val arHasContent = arSnapshot?.let { it.placedCount > 0 || it.completedCount > 0 } == true
    val previewPoints = if (arEngineActive) {
        remember(arSnapshot, measureState.shapeMode) {
            arSnapshot?.takeIf { it.mode == measureState.shapeMode }
                ?.let { SpatialMeasurementEngine.blueprint(it.mode, it.points) }
                .orEmpty()
        }
    } else {
        measureState.points
    }

    val viewportBlock: @Composable (Modifier) -> Unit = { boxMod ->
        Box(modifier = boxMod) {
            // 1. Full-bleed viewport: ARCore world tracking, or the calibrated CameraX/studio canvas
            if (arEngineActive) {
                ArCoreViewport(
                    controller = arController,
                    shapeMode = measureState.shapeMode,
                    displayUnit = prefs.displayUnit,
                    showPlanes = prefs.arShowPlanes,
                    captureHandle = captureHandle,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ArViewportCanvas(
                    cameraActive = effectiveCameraActive,
                    flashlightEnabled = measureState.flashlightEnabled,
                    showSnapGrid = measureState.showSnapGrid,
                    shapeMode = measureState.shapeMode,
                    points = measureState.points,
                    measurementResult = result,
                    displayUnit = prefs.displayUnit,
                    autoOutlineEnabled = measureState.autoOutlineEnabled,
                    isOutlineFrozen = measureState.isOutlineFrozen,
                    hapticEnabled = prefs.hapticFeedback,
                    onOutlineDetected = viewModel::onOutlineDetected,
                    onViewportAspectRatioChanged = viewModel::updateViewportAspectRatio,
                    onDragStarted = viewModel::pushUndoSnapshot,
                    onMovePoint = viewModel::moveAnchorPoint,
                    onAddPolygonPoint = viewModel::addPolygonVertex,
                    captureHandle = captureHandle,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2. Top Subtle Scrim Gradient for Crisp White Camera Controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0x99000000), Color(0x33000000), Color.Transparent)
                        )
                    )
            )

            // 3. Bottom Subtle Scrim Gradient for Pro Shutter Dock
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0x55000000), Color(0xCC000000))
                        )
                    )
            )

            // 4. Floating Top HUD (Cancel/Clear — Status Pill — Save)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .then(if (!isExpanded) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (arEngineActive) arController.clearAll() else viewModel.resetCurrentShape()
                        },
                        modifier = Modifier.testTag("reset_shape_button")
                    ) {
                        Text(
                            text = if (arEngineActive) "Clear" else "Cancel",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (arEngineActive) {
                        ArStatusPill(
                            status = arStatus,
                            tracking = arTracking,
                            reticleKind = arReticleKind,
                            distanceCm = arDistanceCm,
                            displayUnit = prefs.displayUnit,
                            depthActive = arDepthActive
                        )
                    } else {
                        // Center live Level + Auto-Snap glass pill
                        Surface(
                            onClick = { viewModel.setAutoOutlineEnabled(!measureState.autoOutlineEnabled) },
                            shape = RoundedCornerShape(50),
                            color = Color(0x8A111827),
                            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                            modifier = Modifier.testTag("toggle_auto_outline_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (levelReading.isLevel) Icons.Default.AutoAwesome else Icons.Default.Explore,
                                    contentDescription = null,
                                    tint = if (levelReading.isLevel) Color(0xFF34D399) else Color(0xFFFBBF24),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = buildString {
                                        append(if (measureState.autoOutlineEnabled) "AUTO SNAP" else "MANUAL")
                                        if (levelReading.hasSensor) {
                                            append(" • ${UnitConverterEngine.formatValue(levelReading.planeDeviationDeg.toDouble(), 0)}°")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.testTag("save_measurement_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_save),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Floating Glass Shape Mode + Utility Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Measuring engine: ARCore world tracking vs calibrated 2D
                    Surface(
                        onClick = ::toggleEngine,
                        shape = RoundedCornerShape(50),
                        color = if (arEngineActive) Color(0xFF10B981) else Color(0x7A111827),
                        border = BorderStroke(1.dp, if (arEngineActive) Color.White else Color(0x33FFFFFF)),
                        modifier = Modifier.testTag("engine_toggle_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.ViewInAr, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Text(
                                text = if (arEngineActive) "AR" else "2D",
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Sketch: freeze the view and draw lines / shapes on it to measure
                    Surface(
                        onClick = ::startSketch,
                        enabled = !freezingSketch,
                        shape = RoundedCornerShape(50),
                        color = Color(0xE6FBBF24),
                        border = BorderStroke(1.dp, Color.White),
                        modifier = Modifier.testTag("sketch_mode_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (freezingSketch) {
                                CircularProgressIndicator(
                                    color = Color(0xFF111827),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Icon(Icons.Default.Draw, contentDescription = null, tint = Color(0xFF111827), modifier = Modifier.size(15.dp))
                            }
                            Text(
                                text = "Sketch",
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111827)
                            )
                        }
                    }

                    ShapeMode.entries.forEach { mode ->
                        val selected = measureState.shapeMode == mode
                        Surface(
                            onClick = { viewModel.selectShapeMode(mode) },
                            shape = RoundedCornerShape(50),
                            color = if (selected) Color.White else Color(0x7A111827),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selected) Color.White else Color(0x33FFFFFF)
                            ),
                            modifier = Modifier.testTag("shape_chip_${mode.name.lowercase()}")
                        ) {
                            Text(
                                text = if (arEngineActive && mode == ShapeMode.SNAP_3D) "3D Box" else mode.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color(0xFF111827) else Color.White,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
                            )
                        }
                    }

                    // Quick Unit Pill
                    Surface(
                        onClick = ::cycleUnit,
                        shape = RoundedCornerShape(50),
                        color = Color(0xB80A84FF),
                        modifier = Modifier.testTag("quick_unit_cycle_chip")
                    ) {
                        Text(
                            text = prefs.displayUnit.symbol.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }

                    GlassMiniIconPill(
                        onClick = { viewModel.setHighContrast(!prefs.highContrast) },
                        active = prefs.highContrast,
                        testTag = "top_high_contrast_button"
                    ) {
                        Icon(
                            Icons.Default.Contrast,
                            contentDescription = stringResource(R.string.action_high_contrast),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Snap grid (2D) / detected-surface overlay (AR)
                    GlassMiniIconPill(
                        onClick = {
                            if (arEngineActive) viewModel.setArShowPlanes(!prefs.arShowPlanes)
                            else viewModel.toggleSnapGrid()
                        },
                        active = if (arEngineActive) prefs.arShowPlanes else measureState.showSnapGrid,
                        testTag = "toggle_grid_button"
                    ) {
                        Icon(
                            Icons.Default.Grid4x4,
                            contentDescription = if (arEngineActive) "Toggle detected surfaces" else "Toggle snap grid",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    GlassMiniIconPill(
                        onClick = viewModel::toggle3DPreview,
                        active = measureState.show3DPreview,
                        testTag = "toggle_3d_preview_button"
                    ) {
                        Icon(
                            Icons.Default.ViewInAr,
                            contentDescription = "Toggle 3D preview",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    GlassMiniIconPill(
                        onClick = viewModel::openTutorial,
                        active = false,
                        testTag = "top_tutorial_button"
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.action_tutorial),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Compact Non-Intrusive Camera Permission Pill (never pushes/shrinks canvas!)
                AnimatedVisibility(
                    visible = showPermissionPrompt && !hasCameraPermission,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    HudPromptPill(
                        message = "Enable live camera for real-time AR measuring",
                        dismissLabel = "Later",
                        confirmLabel = "Enable",
                        dismissTag = "use_drafting_mode_button",
                        confirmTag = "grant_camera_button",
                        onDismiss = { showPermissionPrompt = false },
                        onConfirm = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    )
                }

                // ARCore install / update prompt
                AnimatedVisibility(
                    visible = effectiveCameraActive &&
                        prefs.arPreferred &&
                        arAvailability == ArAvailability.NEEDS_INSTALL &&
                        !installPromptDismissed,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    HudPromptPill(
                        message = "Install Google Play Services for AR for world-scale measuring",
                        dismissLabel = "Not now",
                        confirmLabel = "Install",
                        dismissTag = "dismiss_ar_install_button",
                        confirmTag = "install_arcore_button",
                        onDismiss = { installPromptDismissed = true },
                        onConfirm = ::requestArInstall
                    )
                }
            }

            // 5. Right-Edge Floating Telemetry Pills (S=..., V=..., P=...)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(bottom = 26.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RightDockedTelemetryPill(
                    text = "S=${result.surfaceAreaDisplay}",
                    onClick = ::cycleUnit,
                    testTag = "telemetry_surface_pill"
                )
                RightDockedTelemetryPill(
                    text = "V=${result.volumeDisplay}",
                    onClick = ::cycleUnit,
                    testTag = "telemetry_volume_pill"
                )
                RightDockedTelemetryPill(
                    text = "P=${result.perimeterDisplay}",
                    onClick = ::cycleUnit,
                    testTag = "telemetry_perimeter_pill"
                )
            }

            // 6. Step coaching (AR) above the dock, then the live 3D miniature
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = measureState.show3DPreview,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IsometricRoomPreview(
                        shapeMode = measureState.shapeMode,
                        points = previewPoints,
                        modifier = Modifier
                            .size(width = 168.dp, height = 106.dp)
                            .clickable { viewModel.toggle3DPreview() }
                            .testTag("isometric_3d_preview")
                    )
                }
                if (arEngineActive) {
                    val coaching = arHint ?: when {
                        arShapeComplete -> "Saved shapes stay anchored — Save it, or tap + to measure another"
                        else -> SpatialMeasurementEngine.stepHint(measureState.shapeMode, placedCount)
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (arHint != null) Color(0xD9B45309) else Color(0xCC111827),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .testTag("ar_coaching_chip")
                    ) {
                        Text(
                            text = coaching,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 7. Floating Done / Freeze + Back controls above the flashlight
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 112.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (arEngineActive) {
                    if (measureState.shapeMode == ShapeMode.POLYGON && placedCount >= 3) {
                        FloatingRoundGlassControl(
                            label = "DONE",
                            active = true,
                            onClick = arController::finishShape,
                            testTag = "finish_shape_button"
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Close area", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                } else if (measureState.autoOutlineEnabled) {
                    FloatingRoundGlassControl(
                        label = if (measureState.isOutlineFrozen) "LOCKED" else "FREEZE",
                        active = measureState.isOutlineFrozen,
                        onClick = viewModel::toggleOutlineFreeze,
                        testTag = "freeze_outline_button"
                    ) {
                        Icon(
                            imageVector = if (measureState.isOutlineFrozen) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Freeze outline",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                val canUndo = if (arEngineActive) arHasContent else measureState.undoStack.isNotEmpty()
                FloatingRoundGlassControl(
                    label = "BACK",
                    active = false,
                    enabled = canUndo,
                    onClick = { if (arEngineActive) arController.undo() else viewModel.undoMeasureEdit() },
                    testTag = "undo_measure_button"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.action_undo),
                        tint = if (canUndo) Color.White else Color(0x66FFFFFF),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 8. Pro Camera Bottom Control Dock (PHOTO — Pure-White + — FLASHLIGHT)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT: PHOTO / CAPTURE Shutter Ring
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0x55FFFFFF))
                            .border(2.5.dp, Color.White, CircleShape)
                            .clickable(enabled = !capturing, onClick = ::onCapturePhoto)
                            .testTag("photo_capture_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (capturing) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x66111827))
                            )
                        }
                    }
                    Text(
                        text = "PHOTO",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // CENTER: Pure-White '+' — places an AR point, adds a 2D vertex or captures
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(12.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = ::onPrimaryAction)
                        .testTag("primary_add_measure_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = when {
                            arEngineActive -> "Place point at reticle"
                            measureState.shapeMode == ShapeMode.POLYGON -> "Add vertex"
                            else -> "Capture measurement"
                        },
                        tint = ArNodeBlue,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // RIGHT: FLASHLIGHT Glass Toggle Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (measureState.flashlightEnabled) ArNodeBlue else Color(0x70111827)
                            )
                            .border(
                                width = 1.5.dp,
                                color = if (measureState.flashlightEnabled) Color.White else Color(0x44FFFFFF),
                                shape = CircleShape
                            )
                            .clickable {
                                if (prefs.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.toggleFlashlight()
                            }
                            .testTag("flashlight_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (measureState.flashlightEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDescription = "Toggle flashlight",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "FLASHLIGHT",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 9. Sketch mode covers the live view (and takes all touches) with the frozen frame.
            sketchSession?.let { session ->
                SketchWorkspace(
                    session = session,
                    editor = sketchEditor,
                    displayUnit = prefs.displayUnit,
                    highContrast = prefs.highContrast,
                    hapticEnabled = prefs.hapticFeedback,
                    saving = savingSketch,
                    padStatusBar = !isExpanded,
                    onExit = { exitSketch() },
                    onSave = ::saveSketch,
                    onCycleUnit = ::cycleUnit,
                    onMessage = viewModel::notifyUser,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (isExpanded) {
        // Two-pane Pro Studio Layout for Tablets & Desktop
        Row(modifier = modifier.fillMaxSize()) {
            viewportBlock(
                Modifier
                    .weight(0.68f)
                    .fillMaxHeight()
            )
            ExpandedTelemetrySidebar(
                result = result,
                calibrationRef = prefs.calibration.referenceName,
                displayUnit = prefs.displayUnit,
                cameraEnabled = effectiveCameraActive,
                engineLabel = when {
                    arEngineActive -> MeasurementSource.ARCORE.label
                    effectiveCameraActive -> MeasurementSource.CALIBRATED.label
                    else -> "Studio drafting (no camera)"
                },
                arFailure = arFailure,
                onUnitSelected = viewModel::setDisplayUnit,
                onToggleCamera = {
                    if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    else viewModel.setCameraEnabled(!measureState.cameraEnabled)
                },
                onToggleEngine = ::toggleEngine,
                onNavigateToCalibrate = onNavigateToCalibrate,
                onSaveClick = { showSaveDialog = true },
                modifier = Modifier
                    .weight(0.32f)
                    .fillMaxHeight()
            )
        }
    } else {
        // Full-bleed edge-to-edge AR Camera screen on mobile!
        viewportBlock(modifier.fillMaxSize())
    }

    if (showSaveDialog) {
        val sketch = pendingSketchSave
        val dialogResult = sketch?.result ?: result
        SaveMeasurementDialog(
            result = dialogResult,
            photoPath = pendingSnapshot,
            sourceLabel = when {
                sketch != null -> "${sketch.source.label} • sketch"
                arEngineActive -> MeasurementSource.ARCORE.label
                else -> MeasurementSource.CALIBRATED.label
            },
            initialTitle = if (sketch != null) {
                "Sketch — ${dialogResult.primaryLabel} ${dialogResult.formattedPrimary}"
            } else {
                "${dialogResult.shapeMode.label} — ${dialogResult.formattedPrimary}"
            },
            initialNotes = if (sketch != null) pendingSketchNotes else "",
            onDismiss = {
                showSaveDialog = false
                pendingSketchSave = null
                viewModel.discardPendingSnapshot()
            },
            onConfirm = { title, notes ->
                if (sketch != null) {
                    viewModel.saveSketchMeasurement(title, notes, sketch)
                } else {
                    viewModel.saveCurrentMeasurement(title, notes)
                }
                pendingSketchSave = null
                showSaveDialog = false
            }
        )
    }

    if (confirmSketchExit) {
        AlertDialog(
            onDismissRequest = { confirmSketchExit = false },
            title = { Text("Discard this sketch?") },
            text = { Text("Shapes you haven't saved will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSketchExit = false
                        exitSketch(discardConfirmed = true)
                    },
                    modifier = Modifier.testTag("confirm_discard_sketch_button")
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSketchExit = false }) { Text("Keep drawing") }
            }
        )
    }
}

@Composable
private fun ArStatusPill(
    status: ArSessionStatus,
    tracking: ArTrackingQuality,
    reticleKind: ArSurfaceKind?,
    distanceCm: Int?,
    displayUnit: LengthDisplayUnit,
    depthActive: Boolean
) {
    val locked = status is ArSessionStatus.Running && tracking == ArTrackingQuality.TRACKING && reticleKind != null
    val label = when {
        status is ArSessionStatus.Starting || status is ArSessionStatus.Idle -> "AR • STARTING"
        status is ArSessionStatus.Failed -> "AR • UNAVAILABLE"
        tracking == ArTrackingQuality.LIMITED || tracking == ArTrackingQuality.STOPPED -> "AR • LIMITED"
        tracking == ArTrackingQuality.INITIALIZING -> "AR • INITIALIZING"
        reticleKind == null -> "AR • SCANNING"
        else -> buildString {
            append("AR • ${reticleKind.label}")
            if (distanceCm != null) {
                val value = distanceCm / displayUnit.cmPerUnit
                append(" • ${UnitConverterEngine.formatValue(value, if (displayUnit.cmPerUnit >= 30) 2 else 1)}${displayUnit.symbol}")
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0x8A111827),
        border = BorderStroke(1.dp, if (locked) Color(0x8834D399) else Color(0x33FFFFFF)),
        modifier = Modifier.testTag("ar_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ViewInAr,
                contentDescription = null,
                tint = if (locked) Color(0xFF34D399) else Color(0xFFFBBF24),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            if (depthActive && locked) {
                Text(
                    text = "DEPTH",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = Color(0xFF93C5FD),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HudPromptPill(
    message: String,
    dismissLabel: String,
    confirmLabel: String,
    dismissTag: String,
    confirmTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xCC111827),
        border = BorderStroke(1.dp, Color(0x55FFFFFF))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = ArNodeBlue, modifier = Modifier.size(18.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) {
                    Text(dismissLabel, color = Color(0xFFCBD5E1))
                }
                Button(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                    Text(confirmLabel)
                }
            }
        }
    }
}

@Composable
private fun RightDockedTelemetryPill(
    text: String,
    onClick: () -> Unit,
    testTag: String
) {
    val tokens = LocalCalibraTokens.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 0.dp, bottomEnd = 0.dp),
        color = if (tokens.highContrast) Color(0xFFFFEE00) else Color(0xF5FFFFFF),
        shadowElevation = 6.dp,
        modifier = Modifier.testTag(testTag)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp
            ),
            color = Color(0xFF111827),
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun GlassMiniIconPill(
    onClick: () -> Unit,
    active: Boolean,
    testTag: String,
    icon: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) ArNodeBlue else Color(0x7A111827),
        border = BorderStroke(1.dp, if (active) Color.White else Color(0x33FFFFFF)),
        modifier = Modifier
            .size(34.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun FloatingRoundGlassControl(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    testTag: String,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Surface(
            onClick = { if (enabled) onClick() },
            shape = CircleShape,
            color = if (active) ArNodeBlue else Color(0x75111827),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier
                .size(44.dp)
                .testTag(testTag)
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = if (enabled) Color.White else Color(0x88FFFFFF),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ExpandedTelemetrySidebar(
    result: ShapeMeasurementResult,
    calibrationRef: String,
    displayUnit: LengthDisplayUnit,
    cameraEnabled: Boolean,
    engineLabel: String,
    arFailure: String?,
    onUnitSelected: (LengthDisplayUnit) -> Unit,
    onToggleCamera: () -> Unit,
    onToggleEngine: () -> Unit,
    onNavigateToCalibrate: () -> Unit,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalCalibraTokens.current
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Live Spatial Telemetry",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${result.shapeMode.label.uppercase()} • ${result.primaryLabel.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = result.formattedPrimary,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("live_primary_readout")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LengthDisplayUnit.entries.forEach { unit ->
                            val selected = unit == displayUnit
                            Surface(
                                onClick = { onUnitSelected(unit) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = unit.symbol,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Breakdown", style = MaterialTheme.typography.titleSmall)
                    TelemetryRow("Surface Area (S)", result.surfaceAreaDisplay)
                    TelemetryRow("Volume (V)", result.volumeDisplay)
                    TelemetryRow("Perimeter (P)", result.perimeterDisplay)
                    result.secondaryMetrics.forEach { (k, v) ->
                        TelemetryRow(k, v)
                    }
                }
            }

            Surface(
                onClick = onToggleEngine,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sidebar_engine_toggle")
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Measuring engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(engineLabel, style = MaterialTheme.typography.titleSmall)
                    if (arFailure != null) {
                        Text(
                            text = "AR unavailable: $arFailure",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = onToggleCamera,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (cameraEnabled) "Camera On" else "Studio", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Surface(
                    onClick = onNavigateToCalibrate,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(calibrationRef.take(10), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Button(
                onClick = onSaveClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.measure_save_measurement))
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SaveMeasurementDialog(
    result: ShapeMeasurementResult,
    photoPath: String?,
    sourceLabel: String,
    initialTitle: String,
    initialNotes: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle.take(64)) }
    var notes by remember { mutableStateOf(initialNotes.take(200)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BookmarkAdd, contentDescription = null) },
        title = { Text("Save Spatial Measurement") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (photoPath != null) {
                    SnapshotImage(
                        path = photoPath,
                        contentDescription = "Captured measurement photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .testTag("save_dialog_photo")
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = result.formattedPrimary,
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "S=${result.surfaceAreaDisplay}  •  V=${result.volumeDisplay}  •  P=${result.perimeterDisplay}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(64) },
                    label = { Text("Title / Room or Object") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_title_input")
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(200) },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_notes_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, notes) },
                modifier = Modifier.testTag("confirm_save_measurement_button")
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
