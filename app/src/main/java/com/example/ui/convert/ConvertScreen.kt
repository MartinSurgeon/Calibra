package com.example.ui.convert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.MeasureCategory
import com.example.domain.MeasureUnit
import com.example.domain.UnitConverterEngine
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCalibraTokens
import com.example.viewmodel.CalibraViewModel
import com.example.viewmodel.ConverterUiState

@Composable
fun ConvertScreen(
    viewModel: CalibraViewModel,
    isExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    val state by viewModel.converterState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CategoryChipsRow(
                    selected = state.category,
                    onSelect = viewModel::selectConverterCategory
                )
                ConverterWorkbenchCard(
                    state = state,
                    onInputChange = viewModel::updateConverterInput,
                    onSelectFrom = viewModel::selectFromUnit,
                    onSelectTo = viewModel::selectToUnit,
                    onSwap = viewModel::swapConverterUnits,
                    onPrecisionChange = viewModel::setConverterPrecision,
                    onCopyOutput = {
                        clipboard.setText(AnnotatedString("${state.formattedOutput} ${state.toUnit.symbol}"))
                        viewModel.notifyUser("Copied ${state.formattedOutput} ${state.toUnit.symbol}")
                    },
                    onSaveToLog = viewModel::saveCurrentConversionToLog
                )
            }

            AllUnitsMatrixCard(
                state = state,
                onRowClick = { unit, formatted ->
                    viewModel.selectToUnit(unit)
                    clipboard.setText(AnnotatedString("$formatted ${unit.symbol}"))
                    viewModel.notifyUser("Selected & copied $formatted ${unit.symbol}")
                },
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight()
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryChipsRow(
                selected = state.category,
                onSelect = viewModel::selectConverterCategory
            )
            ConverterWorkbenchCard(
                state = state,
                onInputChange = viewModel::updateConverterInput,
                onSelectFrom = viewModel::selectFromUnit,
                onSelectTo = viewModel::selectToUnit,
                onSwap = viewModel::swapConverterUnits,
                onPrecisionChange = viewModel::setConverterPrecision,
                onCopyOutput = {
                    clipboard.setText(AnnotatedString("${state.formattedOutput} ${state.toUnit.symbol}"))
                    viewModel.notifyUser("Copied ${state.formattedOutput} ${state.toUnit.symbol}")
                },
                onSaveToLog = viewModel::saveCurrentConversionToLog
            )
            AllUnitsMatrixCard(
                state = state,
                onRowClick = { unit, formatted ->
                    viewModel.selectToUnit(unit)
                    clipboard.setText(AnnotatedString("$formatted ${unit.symbol}"))
                    viewModel.notifyUser("Selected & copied $formatted ${unit.symbol}")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun CategoryChipsRow(
    selected: MeasureCategory,
    onSelect: (MeasureCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MeasureCategory.entries.forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text(cat.label) },
                modifier = Modifier.testTag("converter_category_${cat.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun ConverterWorkbenchCard(
    state: ConverterUiState,
    onInputChange: (String) -> Unit,
    onSelectFrom: (MeasureUnit) -> Unit,
    onSelectTo: (MeasureUnit) -> Unit,
    onSwap: () -> Unit,
    onPrecisionChange: (Int) -> Unit,
    onCopyOutput: () -> Unit,
    onSaveToLog: () -> Unit
) {
    val tokens = LocalCalibraTokens.current
    val units = remember(state.category) { UnitConverterEngine.unitsFor(state.category) }

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
            // FROM input + unit picker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(R.string.convert_from)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("converter_input_field")
                )
                UnitDropdownPicker(
                    selected = state.fromUnit,
                    units = units,
                    onSelect = onSelectFrom,
                    testTag = "converter_from_picker"
                )
            }

            // Quick value chips + Swap button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1", "10", "100", "1000").forEach { preset ->
                        Surface(
                            onClick = { onInputChange(preset) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                FilledTonalIconButton(
                    onClick = onSwap,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("converter_swap_button")
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.convert_swap))
                }
            }

            // TO Result Display
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${stringResource(R.string.convert_to).uppercase()} (${state.toUnit.name})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${state.formattedOutput} ${state.toUnit.symbol}",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testTag("converter_output_readout")
                        )
                    }
                    UnitDropdownPicker(
                        selected = state.toUnit,
                        units = units,
                        onSelect = onSelectTo,
                        testTag = "converter_to_picker"
                    )
                }
            }

            // Precision + Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Decimals:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(2, 3, 4, 6).forEach { d ->
                        val active = state.precisionDecimals == d
                        Surface(
                            onClick = { onPrecisionChange(d) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "$d",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onCopyOutput,
                        modifier = Modifier.testTag("converter_copy_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_copy))
                    }
                    Button(
                        onClick = onSaveToLog,
                        modifier = Modifier.testTag("converter_save_log_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Log")
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitDropdownPicker(
    selected: MeasureUnit,
    units: List<MeasureUnit>,
    onSelect: (MeasureUnit) -> Unit,
    testTag: String
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag(testTag)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected.symbol,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Select unit", modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            units.forEach { unit ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(unit.name)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = unit.symbol,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        onSelect(unit)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AllUnitsMatrixCard(
    state: ConverterUiState,
    onRowClick: (MeasureUnit, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalCalibraTokens.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(tokens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.convert_all_units),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Tap to copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.allEquivalents, key = { it.first.id }) { (unit, formatted) ->
                    val isSelected = unit.id == state.toUnit.id
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRowClick(unit, formatted) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = unit.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = unit.symbol,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "$formatted ${unit.symbol}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }
}
