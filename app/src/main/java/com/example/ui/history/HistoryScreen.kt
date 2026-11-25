package com.example.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.MeasurementEntity
import com.example.data.measurementSource
import com.example.domain.MeasurementSource
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCalibraTokens
import com.example.viewmodel.CalibraViewModel
import com.example.viewmodel.HistorySort
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: CalibraViewModel,
    isExpanded: Boolean,
    onNavigateToMeasure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val items by viewModel.filteredMeasurements.collectAsStateWithLifecycle()
    val totalCount by viewModel.allMeasurements.collectAsStateWithLifecycle()
    val filter by viewModel.historyFilter.collectAsStateWithLifecycle()

    var editingItem by remember { mutableStateOf<MeasurementEntity?>(null) }
    var detailItemId by remember { mutableStateOf<Long?>(null) }

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

    val selectedItem = remember(items, filter.selectedId) {
        items.firstOrNull { it.id == filter.selectedId } ?: items.firstOrNull()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Export & Offline Vault Strip (PDF, CSV, Share, Backup, Restore)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { pdfSaver.launch("calibra_report.pdf") },
                enabled = totalCount.isNotEmpty(),
                modifier = Modifier.testTag("export_pdf_button")
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_export_pdf))
            }
            FilledTonalButton(
                onClick = { csvSaver.launch("calibra_measurements.csv") },
                enabled = totalCount.isNotEmpty(),
                modifier = Modifier.testTag("export_csv_button")
            ) {
                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_export_csv))
            }
            OutlinedButton(
                onClick = { viewModel.sharePdfReport(context) },
                enabled = totalCount.isNotEmpty(),
                modifier = Modifier.testTag("share_pdf_button")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share PDF")
            }
            OutlinedButton(
                onClick = { jsonBackupSaver.launch("calibra_vault_backup.json") },
                enabled = totalCount.isNotEmpty(),
                modifier = Modifier.testTag("backup_json_button")
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_backup_json))
            }
            OutlinedButton(
                onClick = { jsonBackupOpener.launch(arrayOf("application/json", "text/plain")) },
                modifier = Modifier.testTag("restore_json_button")
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_restore_json))
            }
        }

        // Search + Filter Bar
        OutlinedTextField(
            value = filter.query,
            onValueChange = viewModel::updateHistoryQuery,
            placeholder = { Text(stringResource(R.string.history_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_search_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("All", "Length", "Area", "Angle", "Volume", "Mass", "Temp").forEach { cat ->
                FilterChip(
                    selected = filter.selectedCategory == cat,
                    onClick = { viewModel.selectHistoryCategory(cat) },
                    label = { Text(cat) }
                )
            }
            FilterChip(
                selected = filter.pinnedOnly,
                onClick = viewModel::togglePinnedFilter,
                label = { Text("Pinned") },
                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            HistorySort.entries.forEach { sort ->
                FilterChip(
                    selected = filter.sort == sort,
                    onClick = { viewModel.setHistorySort(sort) },
                    label = { Text("Sort: ${sort.label}") }
                )
            }
        }

        // Main Content Area
        if (items.isEmpty()) {
            EmptyHistoryState(
                hasAnyRecords = totalCount.isNotEmpty(),
                onStartMeasuring = onNavigateToMeasure,
                onClearFilter = {
                    viewModel.updateHistoryQuery("")
                    viewModel.selectHistoryCategory("All")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else if (isExpanded) {
            // Canonical List-Detail Layout for Tablets & Desktop
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        MeasurementHistoryCard(
                            item = item,
                            isSelected = item.id == selectedItem?.id,
                            onSelect = { viewModel.selectHistoryItem(item.id) },
                            onTogglePin = { viewModel.togglePin(item) },
                            onEdit = { editingItem = item },
                            onDelete = { viewModel.deleteMeasurement(item) }
                        )
                    }
                }
                if (selectedItem != null) {
                    MeasurementDetailPane(
                        item = selectedItem,
                        onEdit = { editingItem = selectedItem },
                        onDelete = { viewModel.deleteMeasurement(selectedItem) },
                        onTogglePin = { viewModel.togglePin(selectedItem) },
                        onSharePhoto = { viewModel.sharePhoto(context, selectedItem) },
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight()
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    MeasurementHistoryCard(
                        item = item,
                        isSelected = false,
                        onSelect = { detailItemId = item.id },
                        onTogglePin = { viewModel.togglePin(item) },
                        onEdit = { editingItem = item },
                        onDelete = { viewModel.deleteMeasurement(item) }
                    )
                }
            }
        }
    }

    // Phone-sized detail view (tablets show the detail pane side by side).
    detailItemId?.let { id ->
        val item = totalCount.firstOrNull { it.id == id }
        if (item == null) {
            detailItemId = null
        } else {
            Dialog(
                onDismissRequest = { detailItemId = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                MeasurementDetailPane(
                    item = item,
                    onEdit = { editingItem = item },
                    onDelete = {
                        detailItemId = null
                        viewModel.deleteMeasurement(item)
                    },
                    onTogglePin = { viewModel.togglePin(item) },
                    onSharePhoto = { viewModel.sharePhoto(context, item) },
                    onClose = { detailItemId = null },
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.9f)
                        .testTag("history_detail_dialog")
                )
            }
        }
    }

    editingItem?.let { item ->
        EditMeasurementDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSave = { newTitle, newNotes ->
                viewModel.updateMeasurementDetails(item, newTitle, newNotes)
                editingItem = null
            }
        )
    }
}

@Composable
private fun MeasurementHistoryCard(
    item: MeasurementEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = LocalCalibraTokens.current
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else tokens.cardBorderWidth,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("history_item_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(68.dp)) {
                val blueprint: @Composable () -> Unit = {
                    BlueprintThumbnail(
                        shapeModeName = item.shapeMode,
                        pointsJson = item.pointsJson,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (item.photoPath.isNotBlank()) {
                    SnapshotImage(
                        path = item.photoPath,
                        contentDescription = null,
                        maxDimension = 240,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        fallback = blueprint
                    )
                } else {
                    blueprint()
                }
                if (item.measurementSource == MeasurementSource.ARCORE) {
                    SourceBadge(
                        text = MeasurementSource.ARCORE.shortLabel,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${UnitConverterEngine.formatValue(item.primaryValue, 2)} ${item.primaryUnit}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (item.secondarySummary.isNotBlank()) {
                    Text(
                        text = item.secondarySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.category} • ${dateFormat.format(Date(item.createdAtMillis))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        IconButton(onClick = onTogglePin, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (item.pinned) "Unpin" else "Pin",
                                tint = if (item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit measurement",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xE610B981),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun MeasurementDetailPane(
    item: MeasurementEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onSharePhoto: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    val tokens = LocalCalibraTokens.current
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d yyyy • HH:mm", Locale.US) }
    val source = item.measurementSource

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (onClose != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Measurement", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose, modifier = Modifier.testTag("close_detail_button")) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
            if (item.photoPath.isNotBlank()) {
                SnapshotImage(
                    path = item.photoPath,
                    contentDescription = "Photo of ${item.title}",
                    maxDimension = 1080,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("history_detail_photo")
                )
            }
            BlueprintThumbnail(
                shapeModeName = item.shapeMode,
                pointsJson = item.pointsJson,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (item.photoPath.isNotBlank()) 120.dp else 180.dp)
            )
            Text(text = item.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "${UnitConverterEngine.formatValue(item.primaryValue, 3)} ${item.primaryUnit}",
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.primary
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dimensions & Breakdown", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = item.secondarySummary.ifBlank { "Single dimension recorded" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Category: ${item.category} • Mode: ${item.shapeMode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Captured with: ${source.label}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (source == MeasurementSource.ARCORE) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (source == MeasurementSource.CALIBRATED) {
                        Text(
                            text = "Calibration Reference: ${item.calibrationRef}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Recorded: ${dateFormat.format(Date(item.createdAtMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.notes.isNotBlank()) {
                Text("Notes", style = MaterialTheme.typography.titleSmall)
                Text(item.notes, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.photoPath.isNotBlank()) {
                OutlinedButton(
                    onClick = onSharePhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_photo_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share photo")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onTogglePin, modifier = Modifier.weight(1f)) {
                    Icon(if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (item.pinned) "Pinned" else "Pin")
                }
                Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(
    hasAnyRecords: Boolean,
    onStartMeasuring: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_history_1795439827280),
                contentDescription = "Precision drafting tools illustration",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 240.dp, height = 180.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
            Text(
                text = if (hasAnyRecords) "No Matching Records" else stringResource(R.string.history_empty_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = if (hasAnyRecords) {
                    "Try clearing your search or category filter to view all logged measurements."
                } else {
                    stringResource(R.string.history_empty_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = if (hasAnyRecords) onClearFilter else onStartMeasuring,
                modifier = Modifier.testTag("empty_history_cta_button")
            ) {
                Icon(Icons.Default.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (hasAnyRecords) "Clear Filters" else stringResource(R.string.history_start_measuring))
            }
        }
    }
}

@Composable
private fun EditMeasurementDialog(
    item: MeasurementEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    var notes by remember(item.id) { mutableStateOf(item.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Measurement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(64) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(240) },
                    label = { Text("Notes") },
                    minLines = 3,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, notes) }) {
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
