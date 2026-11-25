package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.navigation.CalibraApp
import com.example.ui.theme.CalibraTheme
import com.example.ui.theme.ThemeMode
import com.example.viewmodel.CalibraViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: CalibraViewModel = viewModel(
                factory = CalibraViewModel.provideFactory(applicationContext)
            )
            val prefs by viewModel.preferences.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            var isImmersiveMeasure by remember { mutableStateOf(true) }
            val darkSystemBars = useDark || isImmersiveMeasure

            DisposableEffect(darkSystemBars) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkSystemBars) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (darkSystemBars) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
            }

            CalibraTheme(
                themeMode = prefs.themeMode,
                highContrast = prefs.highContrast
            ) {
                CalibraApp(
                    viewModel = viewModel,
                    onImmersiveMeasureChanged = { isImmersiveMeasure = it }
                )
            }
        }
    }
}
