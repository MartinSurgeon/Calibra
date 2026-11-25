package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.calibrate.CalibrateScreen
import com.example.ui.convert.ConvertScreen
import com.example.ui.history.HistoryScreen
import com.example.ui.measure.MeasureScreen
import com.example.ui.theme.ArNodeBlue
import com.example.ui.theme.ThemeMode
import com.example.ui.tutorial.TutorialOverlayDialog
import com.example.viewmodel.CalibraViewModel

enum class CalibraDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MEASURE("measure", R.string.nav_measure, Icons.Filled.Straighten, Icons.Outlined.Straighten),
    CONVERT("convert", R.string.nav_convert, Icons.Filled.SwapHoriz, Icons.Outlined.SwapHoriz),
    HISTORY("history", R.string.nav_history, Icons.Filled.History, Icons.Outlined.History),
    CALIBRATE("calibrate", R.string.nav_calibrate, Icons.Filled.Tune, Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibraApp(
    viewModel: CalibraViewModel,
    onImmersiveMeasureChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentDest by rememberSaveable { mutableStateOf(CalibraDestination.MEASURE) }
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val showTutorial by viewModel.showTutorial.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = currentDest != CalibraDestination.MEASURE) {
        currentDest = CalibraDestination.MEASURE
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { msg ->
            val isDelete = msg.startsWith("Deleted ")
            val res = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (isDelete) "Undo" else null,
                duration = SnackbarDuration.Short
            )
            if (isDelete && res == SnackbarResult.ActionPerformed) {
                viewModel.undoDeleteMeasurement()
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        val isExpanded = maxWidth >= 840.dp
        val isImmersiveMeasure = currentDest == CalibraDestination.MEASURE && !useRail

        LaunchedEffect(isImmersiveMeasure) {
            onImmersiveMeasureChanged(isImmersiveMeasure)
        }

        Scaffold(
            contentWindowInsets = if (isImmersiveMeasure) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // Full-bleed edge-to-edge camera on Measure; clean Editorial Studio TopAppBar on other tabs
                if (!isImmersiveMeasure) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "3D",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = when (currentDest) {
                                        CalibraDestination.MEASURE -> stringResource(R.string.app_name)
                                        CalibraDestination.CONVERT -> stringResource(R.string.convert_title)
                                        CalibraDestination.HISTORY -> stringResource(R.string.history_title)
                                        CalibraDestination.CALIBRATE -> stringResource(R.string.calibrate_title)
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { viewModel.setHighContrast(!prefs.highContrast) },
                                modifier = Modifier.testTag("top_high_contrast_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Contrast,
                                    contentDescription = stringResource(R.string.action_high_contrast),
                                    tint = if (prefs.highContrast) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = viewModel::cycleThemeMode,
                                modifier = Modifier.testTag("top_theme_toggle_button")
                            ) {
                                Icon(
                                    imageVector = when (prefs.themeMode) {
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                        ThemeMode.SYSTEM -> Icons.Default.DarkMode
                                    },
                                    contentDescription = stringResource(R.string.action_toggle_theme),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = viewModel::openTutorial,
                                modifier = Modifier.testTag("top_tutorial_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HelpOutline,
                                    contentDescription = stringResource(R.string.action_tutorial),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (!useRail) {
                    val darkDock = isImmersiveMeasure
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = if (darkDock) Color(0xFF0A0D12) else MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        CalibraDestination.entries.forEach { dest ->
                            val selected = currentDest == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentDest = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = if (darkDock) {
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Color.White,
                                        indicatorColor = ArNodeBlue,
                                        unselectedIconColor = Color(0xFF94A3B8),
                                        unselectedTextColor = Color(0xFF94A3B8)
                                    )
                                } else {
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        CalibraDestination.entries.forEach { dest ->
                            val selected = currentDest == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentDest = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentDest) {
                        CalibraDestination.MEASURE -> MeasureScreen(
                            viewModel = viewModel,
                            isExpanded = isExpanded,
                            onNavigateToCalibrate = { currentDest = CalibraDestination.CALIBRATE }
                        )
                        CalibraDestination.CONVERT -> ConvertScreen(
                            viewModel = viewModel,
                            isExpanded = isExpanded
                        )
                        CalibraDestination.HISTORY -> HistoryScreen(
                            viewModel = viewModel,
                            isExpanded = isExpanded,
                            onNavigateToMeasure = { currentDest = CalibraDestination.MEASURE }
                        )
                        CalibraDestination.CALIBRATE -> CalibrateScreen(
                            viewModel = viewModel,
                            isExpanded = isExpanded
                        )
                    }
                }
            }
        }
    }

    if (showTutorial) {
        TutorialOverlayDialog(
            onDismiss = { viewModel.dismissTutorial(markCompleted = true) },
            onSelectShapeAndFinish = { chosenMode ->
                viewModel.selectShapeMode(chosenMode)
                viewModel.dismissTutorial(markCompleted = true)
                currentDest = CalibraDestination.MEASURE
            }
        )
    }
}
