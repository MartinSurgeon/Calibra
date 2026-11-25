package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ar.ArAvailability
import com.example.ar.ArCoreSupport
import com.example.ar.ArMeasurementSnapshot
import com.example.data.AppPreferences
import com.example.data.DeviceLevelSensor
import com.example.data.ExportManager
import com.example.data.LevelReading
import com.example.data.MeasurementDatabase
import com.example.data.MeasurementEntity
import com.example.data.MeasurementRepository
import com.example.data.PreferencesRepository
import com.example.data.SnapshotStore
import com.example.domain.CalibrationPreset
import com.example.domain.CalibrationProfile
import com.example.domain.DetectedOutline
import com.example.domain.GeometryEngine
import com.example.domain.LengthDisplayUnit
import com.example.domain.MeasureCategory
import com.example.domain.MeasureUnit
import com.example.domain.MeasurementSource
import com.example.domain.NormPoint
import com.example.domain.ShapeMeasurementResult
import com.example.domain.ShapeMode
import com.example.domain.SpatialMeasurementEngine
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistorySort(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    LARGEST("Largest"),
    NAME("A–Z")
}

data class MeasureUiState(
    val shapeMode: ShapeMode = ShapeMode.SNAP_3D,
    val points: List<NormPoint> = GeometryEngine.defaultPointsFor(ShapeMode.SNAP_3D),
    val autoOutlineEnabled: Boolean = true,
    val isOutlineFrozen: Boolean = false,
    val flashlightEnabled: Boolean = false,
    val show3DPreview: Boolean = true,
    val showSnapGrid: Boolean = true,
    val trackingConfidence: Float = 0f,
    val trackingStatus: String = "READY",
    val viewportAspectRatio: Float = 1.65f,
    val cameraEnabled: Boolean = true,
    val undoStack: List<List<NormPoint>> = emptyList()
)

data class ConverterUiState(
    val category: MeasureCategory = MeasureCategory.LENGTH,
    val inputText: String = "100",
    val fromUnit: MeasureUnit = UnitConverterEngine.findById("cm")!!,
    val toUnit: MeasureUnit = UnitConverterEngine.findById("in")!!,
    val precisionDecimals: Int = 3
) {
    val parsedInput: Double
        get() = inputText.toDoubleOrNull() ?: 0.0

    val convertedValue: Double
        get() = UnitConverterEngine.convert(parsedInput, fromUnit, toUnit)

    val formattedOutput: String
        get() = UnitConverterEngine.formatValue(convertedValue, precisionDecimals)

    val allEquivalents: List<Pair<MeasureUnit, String>>
        get() = UnitConverterEngine.unitsFor(category).map { target ->
            val result = UnitConverterEngine.convert(parsedInput, fromUnit, target)
            target to UnitConverterEngine.formatValue(result, precisionDecimals)
        }
}

data class HistoryFilterState(
    val query: String = "",
    val selectedCategory: String = "All",
    val pinnedOnly: Boolean = false,
    val sort: HistorySort = HistorySort.NEWEST,
    val selectedId: Long? = null
)

class CalibraViewModel(
    private val measurementRepo: MeasurementRepository,
    private val prefsRepo: PreferencesRepository,
    levelSensor: DeviceLevelSensor,
    private val snapshotStore: SnapshotStore,
    private val checkArAvailability: suspend () -> ArAvailability
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = prefsRepo.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = AppPreferences()
    )

    val levelReading: StateFlow<LevelReading> = levelSensor.observeLevel().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = LevelReading()
    )

    val allMeasurements: StateFlow<List<MeasurementEntity>> = measurementRepo.allMeasurements.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    private val _measureState = MutableStateFlow(MeasureUiState())
    val measureState: StateFlow<MeasureUiState> = _measureState.asStateFlow()

    private val _converterState = MutableStateFlow(ConverterUiState())
    val converterState: StateFlow<ConverterUiState> = _converterState.asStateFlow()

    private val _historyFilter = MutableStateFlow(HistoryFilterState())
    val historyFilter: StateFlow<HistoryFilterState> = _historyFilter.asStateFlow()

    private val _showTutorial = MutableStateFlow(false)
    val showTutorial: StateFlow<Boolean> = _showTutorial.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private var lastDeletedMeasurement: MeasurementEntity? = null
    private var initialTutorialChecked = false

    private val _arAvailability = MutableStateFlow(ArAvailability.CHECKING)
    val arAvailability: StateFlow<ArAvailability> = _arAvailability.asStateFlow()

    /** Focused ARCore measurement while the AR engine drives the Measure screen, else null. */
    private val _arMeasurement = MutableStateFlow<ArMeasurementSnapshot?>(null)
    val arMeasurement: StateFlow<ArMeasurementSnapshot?> = _arMeasurement.asStateFlow()

    /** Photo captured for the measurement about to be saved. */
    private val _pendingSnapshotPath = MutableStateFlow<String?>(null)
    val pendingSnapshotPath: StateFlow<String?> = _pendingSnapshotPath.asStateFlow()

    val liveMeasurementResult: StateFlow<ShapeMeasurementResult> = combine(
        _measureState,
        preferences,
        levelReading,
        _arMeasurement
    ) { measure, prefs, level, ar ->
        if (ar != null) {
            // Ignore a snapshot from a previous mode until the AR engine catches up.
            val points = if (ar.mode == measure.shapeMode) ar.points else emptyList()
            SpatialMeasurementEngine.calculate(measure.shapeMode, points, prefs.displayUnit)
        } else {
            GeometryEngine.calculate(
                mode = measure.shapeMode,
                points = measure.points,
                aspectRatioHeightOverWidth = measure.viewportAspectRatio,
                calibration = prefs.calibration,
                displayUnit = prefs.displayUnit,
                tiltDegrees = if (level.hasSensor) level.planeDeviationDeg else 0f
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GeometryEngine.calculate(
            ShapeMode.SNAP_3D,
            GeometryEngine.defaultPointsFor(ShapeMode.SNAP_3D),
            1.65f,
            CalibrationProfile(),
            LengthDisplayUnit.M
        )
    )

    val filteredMeasurements: StateFlow<List<MeasurementEntity>> = combine(
        allMeasurements,
        _historyFilter
    ) { list, filter ->
        val q = filter.query.trim().lowercase()
        val filtered = list.filter { item ->
            val matchesQuery = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.category.lowercase().contains(q) ||
                item.shapeMode.lowercase().contains(q) ||
                item.notes.lowercase().contains(q) ||
                item.secondarySummary.lowercase().contains(q)
            val matchesCat = filter.selectedCategory == "All" ||
                item.category.equals(filter.selectedCategory, ignoreCase = true)
            val matchesPin = !filter.pinnedOnly || item.pinned
            matchesQuery && matchesCat && matchesPin
        }
        when (filter.sort) {
            HistorySort.NEWEST -> filtered.sortedWith(compareByDescending<MeasurementEntity> { it.pinned }.thenByDescending { it.createdAtMillis })
            HistorySort.OLDEST -> filtered.sortedWith(compareByDescending<MeasurementEntity> { it.pinned }.thenBy { it.createdAtMillis })
            HistorySort.LARGEST -> filtered.sortedByDescending { it.primaryValue }
            HistorySort.NAME -> filtered.sortedBy { it.title.lowercase() }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                if (!initialTutorialChecked) {
                    initialTutorialChecked = true
                    if (!prefs.tutorialCompleted) {
                        _showTutorial.value = true
                    }
                }
            }
        }
        refreshArAvailability()
        viewModelScope.launch {
            // Remove photos left behind by deletions from earlier sessions.
            val referenced = measurementRepo.allMeasurements.first()
                .mapNotNull { it.photoPath.takeIf(String::isNotBlank) }
                .toSet()
            snapshotStore.pruneOrphans(referenced)
        }
    }

    // --- AR Engine ---

    fun refreshArAvailability() {
        viewModelScope.launch {
            _arAvailability.value = checkArAvailability()
        }
    }

    fun updateArMeasurement(snapshot: ArMeasurementSnapshot?) {
        _arMeasurement.value = snapshot
    }

    fun setArPreferred(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setArPreferred(enabled) }
    }

    fun setArShowPlanes(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setArShowPlanes(enabled) }
    }

    fun setArDepthAssist(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setArDepthAssist(enabled) }
    }

    // --- Measurement Photos ---

    /** Persists a captured viewport photo; it is attached to the next saved measurement. */
    suspend fun storePendingSnapshot(bitmap: Bitmap): Boolean {
        discardPendingSnapshot()
        val result = snapshotStore.save(bitmap)
        result.onSuccess { _pendingSnapshotPath.value = it }
            .onFailure { _snackbarMessages.tryEmit("Couldn't save photo: ${it.localizedMessage}") }
        return result.isSuccess
    }

    fun discardPendingSnapshot() {
        val path = _pendingSnapshotPath.value ?: return
        _pendingSnapshotPath.value = null
        viewModelScope.launch { snapshotStore.delete(path) }
    }

    fun sharePhoto(context: Context, item: MeasurementEntity) {
        if (item.photoPath.isBlank()) return
        runCatching {
            ExportManager.launchShareIntent(
                context,
                snapshotStore.shareUri(item.photoPath),
                "image/jpeg",
                "${item.title} — ${UnitConverterEngine.formatValue(item.primaryValue, 2)} ${item.primaryUnit}"
            )
        }.onFailure {
            _snackbarMessages.tryEmit("Unable to share photo: ${it.localizedMessage}")
        }
    }

    // --- Measure Actions ---

    fun selectShapeMode(mode: ShapeMode) {
        _measureState.update { state ->
            state.copy(
                shapeMode = mode,
                points = GeometryEngine.defaultPointsFor(mode),
                isOutlineFrozen = false,
                undoStack = emptyList()
            )
        }
    }

    fun setAutoOutlineEnabled(enabled: Boolean) {
        _measureState.update {
            it.copy(
                autoOutlineEnabled = enabled,
                isOutlineFrozen = if (!enabled) false else it.isOutlineFrozen,
                trackingStatus = if (enabled) "SCANNING" else "MANUAL"
            )
        }
    }

    fun toggleOutlineFreeze() {
        _measureState.update { it.copy(isOutlineFrozen = !it.isOutlineFrozen) }
    }

    fun toggleFlashlight() {
        _measureState.update { it.copy(flashlightEnabled = !it.flashlightEnabled) }
    }

    fun toggle3DPreview() {
        _measureState.update { it.copy(show3DPreview = !it.show3DPreview) }
    }

    fun toggleSnapGrid() {
        _measureState.update { it.copy(showSnapGrid = !it.showSnapGrid) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        _measureState.update { it.copy(cameraEnabled = enabled) }
    }

    fun updateViewportAspectRatio(ratio: Float) {
        if (ratio > 0.3f && ratio < 4f) {
            _measureState.update { it.copy(viewportAspectRatio = ratio) }
        }
    }

    fun onOutlineDetected(outline: DetectedOutline) {
        _measureState.update { state ->
            if (!state.autoOutlineEnabled || state.isOutlineFrozen) {
                state
            } else {
                state.copy(
                    points = outline.points,
                    trackingConfidence = outline.confidence,
                    trackingStatus = outline.statusLabel
                )
            }
        }
    }

    fun moveAnchorPoint(index: Int, newPoint: NormPoint) {
        _measureState.update { state ->
            if (index !in state.points.indices) return@update state
            val clamped = NormPoint(newPoint.x.coerceIn(0.03f, 0.97f), newPoint.y.coerceIn(0.03f, 0.97f))
            val updated = state.points.toMutableList()
            updated[index] = clamped
            state.copy(
                points = updated,
                autoOutlineEnabled = false,
                trackingStatus = "MANUAL"
            )
        }
    }

    fun pushUndoSnapshot() {
        _measureState.update { state ->
            val stack = (state.undoStack + listOf(state.points)).takeLast(15)
            state.copy(undoStack = stack)
        }
    }

    fun addPolygonVertex(point: NormPoint) {
        _measureState.update { state ->
            if (state.shapeMode != ShapeMode.POLYGON || state.points.size >= 12) return@update state
            val clamped = NormPoint(point.x.coerceIn(0.04f, 0.96f), point.y.coerceIn(0.04f, 0.96f))
            state.copy(
                undoStack = (state.undoStack + listOf(state.points)).takeLast(15),
                points = state.points + clamped,
                autoOutlineEnabled = false,
                trackingStatus = "MANUAL"
            )
        }
    }

    fun undoMeasureEdit() {
        _measureState.update { state ->
            val prev = state.undoStack.lastOrNull() ?: return@update state
            state.copy(
                points = prev,
                undoStack = state.undoStack.dropLast(1)
            )
        }
    }

    fun resetCurrentShape() {
        _measureState.update { state ->
            state.copy(
                undoStack = (state.undoStack + listOf(state.points)).takeLast(15),
                points = GeometryEngine.defaultPointsFor(state.shapeMode),
                isOutlineFrozen = false
            )
        }
    }

    fun saveCurrentMeasurement(title: String, notes: String) {
        val result = liveMeasurementResult.value
        val state = _measureState.value
        val cal = preferences.value.calibration
        val ar = _arMeasurement.value?.takeIf { it.mode == state.shapeMode }
        val photo = _pendingSnapshotPath.value.orEmpty()
        _pendingSnapshotPath.value = null
        val cleanTitle = title.trim().ifBlank { "${result.shapeMode.label} Measurement" }
        val entity = MeasurementEntity(
            title = cleanTitle,
            category = result.categoryName,
            shapeMode = result.shapeMode.name,
            primaryValue = result.primaryValue,
            primaryUnit = result.primaryUnitSymbol,
            secondarySummary = result.secondarySummary,
            notes = notes.trim(),
            calibrationRef = if (ar != null) "ARCore world tracking" else cal.referenceName,
            pointsJson = if (ar != null) {
                GeometryEngine.serializePoints(SpatialMeasurementEngine.blueprint(state.shapeMode, ar.points))
            } else {
                GeometryEngine.serializePoints(state.points)
            },
            createdAtMillis = System.currentTimeMillis(),
            source = if (ar != null) MeasurementSource.ARCORE.name else MeasurementSource.CALIBRATED.name,
            worldPointsJson = ar?.let { SpatialMeasurementEngine.serialize(it.points) }.orEmpty(),
            photoPath = photo
        )
        viewModelScope.launch {
            measurementRepo.insert(entity)
            _snackbarMessages.tryEmit(
                if (photo.isNotBlank()) "Saved \"$cleanTitle\" with photo" else "Saved \"$cleanTitle\" to offline log"
            )
        }
    }

    // --- Converter Actions ---

    fun selectConverterCategory(category: MeasureCategory) {
        val units = UnitConverterEngine.unitsFor(category)
        val from = units.first()
        val to = units.getOrElse(1) { units.first() }
        _converterState.update {
            it.copy(category = category, fromUnit = from, toUnit = to)
        }
    }

    fun updateConverterInput(raw: String) {
        val sanitized = raw.filter { it.isDigit() || it == '.' || it == '-' }.take(16)
        _converterState.update { it.copy(inputText = sanitized) }
    }

    fun selectFromUnit(unit: MeasureUnit) {
        _converterState.update { it.copy(fromUnit = unit) }
    }

    fun selectToUnit(unit: MeasureUnit) {
        _converterState.update { it.copy(toUnit = unit) }
    }

    fun swapConverterUnits() {
        _converterState.update { it.copy(fromUnit = it.toUnit, toUnit = it.fromUnit) }
    }

    fun setConverterPrecision(decimals: Int) {
        _converterState.update { it.copy(precisionDecimals = decimals.coerceIn(1, 6)) }
    }

    fun saveCurrentConversionToLog() {
        val state = _converterState.value
        val inputFormatted = UnitConverterEngine.formatValue(state.parsedInput, state.precisionDecimals)
        val entity = MeasurementEntity(
            title = "${state.category.label}: $inputFormatted ${state.fromUnit.symbol} → ${state.toUnit.symbol}",
            category = state.category.label,
            shapeMode = "CONVERSION",
            primaryValue = state.convertedValue,
            primaryUnit = state.toUnit.symbol,
            secondarySummary = "Converted from $inputFormatted ${state.fromUnit.name} to ${state.toUnit.name}",
            notes = "Precision: ${state.precisionDecimals} decimals",
            calibrationRef = "Exact Formula",
            createdAtMillis = System.currentTimeMillis()
        )
        viewModelScope.launch {
            measurementRepo.insert(entity)
            _snackbarMessages.tryEmit("Conversion logged to history")
        }
    }

    // --- History Actions ---

    fun updateHistoryQuery(query: String) {
        _historyFilter.update { it.copy(query = query) }
    }

    fun selectHistoryCategory(category: String) {
        _historyFilter.update { it.copy(selectedCategory = category) }
    }

    fun togglePinnedFilter() {
        _historyFilter.update { it.copy(pinnedOnly = !it.pinnedOnly) }
    }

    fun setHistorySort(sort: HistorySort) {
        _historyFilter.update { it.copy(sort = sort) }
    }

    fun selectHistoryItem(id: Long?) {
        _historyFilter.update { it.copy(selectedId = id) }
    }

    fun togglePin(item: MeasurementEntity) {
        viewModelScope.launch {
            measurementRepo.togglePin(item.id, !item.pinned)
        }
    }

    fun updateMeasurementDetails(item: MeasurementEntity, newTitle: String, newNotes: String) {
        viewModelScope.launch {
            measurementRepo.update(
                item.copy(
                    title = newTitle.trim().ifBlank { item.title },
                    notes = newNotes.trim()
                )
            )
            _snackbarMessages.tryEmit("Measurement updated")
        }
    }

    fun deleteMeasurement(item: MeasurementEntity) {
        lastDeletedMeasurement = item
        viewModelScope.launch {
            measurementRepo.deleteById(item.id)
            _snackbarMessages.tryEmit("Deleted \"${item.title}\"")
        }
    }

    fun undoDeleteMeasurement() {
        val item = lastDeletedMeasurement ?: return
        lastDeletedMeasurement = null
        viewModelScope.launch {
            measurementRepo.insert(item.copy(id = 0L))
            _snackbarMessages.tryEmit("Restored \"${item.title}\"")
        }
    }

    // --- Export & Backup Actions ---

    fun exportPdfToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = ExportManager.writePdfToUri(context, uri, allMeasurements.value)
            _snackbarMessages.tryEmit(
                if (result.isSuccess) "PDF Report saved successfully"
                else "PDF export failed: ${result.exceptionOrNull()?.localizedMessage}"
            )
        }
    }

    fun exportCsvToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = ExportManager.writeCsvToUri(context, uri, allMeasurements.value)
            _snackbarMessages.tryEmit(
                if (result.isSuccess) "CSV Spreadsheet saved successfully"
                else "CSV export failed: ${result.exceptionOrNull()?.localizedMessage}"
            )
        }
    }

    fun exportBackupToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = ExportManager.writeBackupJsonToUri(context, uri, allMeasurements.value)
            _snackbarMessages.tryEmit(
                if (result.isSuccess) "Offline vault backup saved (${allMeasurements.value.size} items)"
                else "Backup failed: ${result.exceptionOrNull()?.localizedMessage}"
            )
        }
    }

    fun importBackupFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = ExportManager.readBackupJsonFromUri(context, uri)
            result.onSuccess { items ->
                if (items.isEmpty()) {
                    _snackbarMessages.tryEmit("Backup file contains no measurements")
                } else {
                    measurementRepo.insertAll(items)
                    _snackbarMessages.tryEmit("Restored ${items.size} measurements into offline vault")
                }
            }.onFailure { err ->
                _snackbarMessages.tryEmit("Restore failed: ${err.localizedMessage ?: "Invalid file"}")
            }
        }
    }

    fun sharePdfReport(context: Context) {
        viewModelScope.launch {
            ExportManager.createShareablePdf(context, allMeasurements.value)
                .onSuccess { uri ->
                    ExportManager.launchShareIntent(context, uri, "application/pdf", "Calibra Measurement Report (PDF)")
                }
                .onFailure {
                    _snackbarMessages.tryEmit("Unable to share PDF: ${it.localizedMessage}")
                }
        }
    }

    fun shareCsvLog(context: Context) {
        viewModelScope.launch {
            ExportManager.createShareableCsv(context, allMeasurements.value)
                .onSuccess { uri ->
                    ExportManager.launchShareIntent(context, uri, "text/csv", "Calibra Measurement Log (CSV)")
                }
                .onFailure {
                    _snackbarMessages.tryEmit("Unable to share CSV: ${it.localizedMessage}")
                }
        }
    }

    // --- Calibration & Settings Actions ---

    fun applyReferenceCalibration(
        preset: CalibrationPreset,
        knownCm: Double,
        normalizedSpan: Float,
        workingDistanceCm: Float
    ) {
        val cmPerWidth = GeometryEngine.calibrateFromSpan(knownCm, normalizedSpan)
        val label = if (preset == CalibrationPreset.CUSTOM) {
            "Custom (${UnitConverterEngine.formatValue(knownCm, 1)} cm)"
        } else {
            preset.label
        }
        val current = preferences.value.calibration
        val updated = current.copy(
            cmPerNormWidth = cmPerWidth,
            workingDistanceCm = workingDistanceCm,
            referenceName = label,
            lastCalibratedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            prefsRepo.saveCalibration(updated)
            _snackbarMessages.tryEmit("Calibrated with $label (Scale: ${UnitConverterEngine.formatValue(cmPerWidth.toDouble(), 1)} cm/view)")
        }
    }

    fun setTiltCompensation(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferences.value.calibration
            prefsRepo.saveCalibration(current.copy(tiltCompensationEnabled = enabled))
        }
    }

    fun updateWorkingDistance(distanceCm: Float) {
        viewModelScope.launch {
            val current = preferences.value.calibration
            val ratio = (distanceCm / current.workingDistanceCm.coerceAtLeast(10f)).coerceIn(0.25f, 4f)
            val updated = current.copy(
                workingDistanceCm = distanceCm,
                cmPerNormWidth = (current.cmPerNormWidth * ratio).coerceIn(5f, 3000f)
            )
            prefsRepo.saveCalibration(updated)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    }

    fun cycleThemeMode() {
        val next = when (preferences.value.themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.SYSTEM
        }
        setThemeMode(next)
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setHighContrast(enabled) }
    }

    fun setDisplayUnit(unit: LengthDisplayUnit) {
        viewModelScope.launch { prefsRepo.setDisplayUnit(unit) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setHapticFeedback(enabled) }
    }

    fun openTutorial() {
        _showTutorial.value = true
    }

    fun dismissTutorial(markCompleted: Boolean = true) {
        _showTutorial.value = false
        if (markCompleted) {
            viewModelScope.launch { prefsRepo.setTutorialCompleted(true) }
        }
    }

    fun notifyUser(message: String) {
        _snackbarMessages.tryEmit(message)
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appCtx = context.applicationContext
                    val db = MeasurementDatabase.getInstance(appCtx)
                    val repo = MeasurementRepository(db.measurementDao())
                    val prefs = PreferencesRepository(appCtx)
                    val sensor = DeviceLevelSensor(appCtx)
                    return CalibraViewModel(
                        measurementRepo = repo,
                        prefsRepo = prefs,
                        levelSensor = sensor,
                        snapshotStore = SnapshotStore(appCtx),
                        checkArAvailability = { ArCoreSupport.checkAvailability(appCtx) }
                    ) as T
                }
            }
    }
}
