package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.CalibrationPreset
import com.example.domain.CalibrationProfile
import com.example.domain.LengthDisplayUnit
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibraDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calibra_preferences"
)

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val highContrast: Boolean = false,
    val displayUnit: LengthDisplayUnit = LengthDisplayUnit.M,
    val hapticFeedback: Boolean = true,
    val tutorialCompleted: Boolean = false,
    val calibration: CalibrationProfile = CalibrationProfile()
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val DISPLAY_UNIT = stringPreferencesKey("display_unit")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val TUTORIAL_COMPLETED = booleanPreferencesKey("tutorial_completed")
        val CM_PER_NORM_WIDTH = floatPreferencesKey("cm_per_norm_width")
        val WORKING_DISTANCE_CM = floatPreferencesKey("working_distance_cm")
        val CALIBRATION_REF_NAME = stringPreferencesKey("calibration_ref_name")
        val TILT_COMPENSATION = booleanPreferencesKey("tilt_compensation")
        val LAST_CALIBRATED_AT = longPreferencesKey("last_calibrated_at")
    }

    val preferencesFlow: Flow<AppPreferences> = context.calibraDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = ThemeMode.entries.firstOrNull { it.name == themeStr } ?: ThemeMode.SYSTEM
        val unitId = prefs[Keys.DISPLAY_UNIT] ?: LengthDisplayUnit.M.id

        AppPreferences(
            themeMode = themeMode,
            highContrast = prefs[Keys.HIGH_CONTRAST] ?: false,
            displayUnit = LengthDisplayUnit.fromId(unitId),
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            tutorialCompleted = prefs[Keys.TUTORIAL_COMPLETED] ?: false,
            calibration = CalibrationProfile(
                cmPerNormWidth = prefs[Keys.CM_PER_NORM_WIDTH] ?: 360f,
                workingDistanceCm = prefs[Keys.WORKING_DISTANCE_CM] ?: 240f,
                referenceName = prefs[Keys.CALIBRATION_REF_NAME]
                    ?: CalibrationPreset.DOOR_WIDTH.label,
                tiltCompensationEnabled = prefs[Keys.TILT_COMPENSATION] ?: true,
                lastCalibratedAt = prefs[Keys.LAST_CALIBRATED_AT] ?: 0L
            )
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.calibraDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        context.calibraDataStore.edit { it[Keys.HIGH_CONTRAST] = enabled }
    }

    suspend fun setDisplayUnit(unit: LengthDisplayUnit) {
        context.calibraDataStore.edit { it[Keys.DISPLAY_UNIT] = unit.id }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.calibraDataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setTutorialCompleted(completed: Boolean) {
        context.calibraDataStore.edit { it[Keys.TUTORIAL_COMPLETED] = completed }
    }

    suspend fun saveCalibration(profile: CalibrationProfile) {
        context.calibraDataStore.edit { prefs ->
            prefs[Keys.CM_PER_NORM_WIDTH] = profile.cmPerNormWidth
            prefs[Keys.WORKING_DISTANCE_CM] = profile.workingDistanceCm
            prefs[Keys.CALIBRATION_REF_NAME] = profile.referenceName
            prefs[Keys.TILT_COMPENSATION] = profile.tiltCompensationEnabled
            prefs[Keys.LAST_CALIBRATED_AT] = profile.lastCalibratedAt
        }
    }
}
