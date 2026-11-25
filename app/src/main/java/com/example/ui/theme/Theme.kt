package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

@Immutable
data class CalibraVisualTokens(
    val laserPrimary: Color,
    val laserSecondary: Color,
    val laserWarning: Color,
    val arNodeBlue: Color,
    val cardBorderWidth: Dp,
    val highContrast: Boolean
)

val LocalCalibraTokens = staticCompositionLocalOf {
    CalibraVisualTokens(
        laserPrimary = ArLineWhite,
        laserSecondary = ArAccentEmerald,
        laserWarning = ArAccentAmber,
        arNodeBlue = ArNodeBlue,
        cardBorderWidth = 1.dp,
        highContrast = false
    )
}

private val StandardLightScheme = lightColorScheme(
    primary = RoyalBluePrimaryLight,
    onPrimary = OnRoyalBlueLight,
    primaryContainer = RoyalBlueContainerLight,
    onPrimaryContainer = OnRoyalBlueContainerLight,
    secondary = SlateTealSecondaryLight,
    onSecondary = OnSlateTealLight,
    secondaryContainer = SlateTealContainerLight,
    onSecondaryContainer = OnSlateTealContainerLight,
    tertiary = WarmBronzeTertiaryLight,
    onTertiary = OnWarmBronzeLight,
    tertiaryContainer = WarmBronzeContainerLight,
    onTertiaryContainer = OnWarmBronzeContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnBackgroundLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = ErrorRedLight,
    onError = Color.White
)

private val StandardDarkScheme = darkColorScheme(
    primary = RoyalBluePrimaryDark,
    onPrimary = OnRoyalBlueDark,
    primaryContainer = RoyalBlueContainerDark,
    onPrimaryContainer = OnRoyalBlueContainerDark,
    secondary = MintSecondaryDark,
    onSecondary = OnMintDark,
    secondaryContainer = MintContainerDark,
    onSecondaryContainer = OnMintContainerDark,
    tertiary = GoldTertiaryDark,
    onTertiary = OnGoldDark,
    tertiaryContainer = GoldContainerDark,
    onTertiaryContainer = OnGoldContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = ErrorRedDark,
    onError = Color.Black
)

private val HighContrastLightScheme = lightColorScheme(
    primary = HcLightPrimary,
    onPrimary = HcLightOnPrimary,
    primaryContainer = HcLightPrimaryContainer,
    onPrimaryContainer = HcLightOnSurface,
    secondary = HcLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB3F2DD),
    onSecondaryContainer = HcLightOnSurface,
    tertiary = Color(0xFF7A2E00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9B3),
    onTertiaryContainer = HcLightOnSurface,
    background = HcLightBackground,
    onBackground = HcLightOnSurface,
    surface = HcLightSurface,
    onSurface = HcLightOnSurface,
    surfaceVariant = HcLightSurfaceVariant,
    onSurfaceVariant = HcLightOnSurface,
    outline = HcLightOutline,
    error = Color(0xFFA80000),
    onError = Color.White
)

private val HighContrastDarkScheme = darkColorScheme(
    primary = HcDarkPrimary,
    onPrimary = HcDarkOnPrimary,
    primaryContainer = HcDarkPrimaryContainer,
    onPrimaryContainer = HcDarkOnSurface,
    secondary = HcDarkSecondary,
    onSecondary = HcDarkOnSecondary,
    secondaryContainer = Color(0xFF004033),
    onSecondaryContainer = HcDarkOnSurface,
    tertiary = Color(0xFFFF9E00),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF4D2E00),
    onTertiaryContainer = HcDarkOnSurface,
    background = HcDarkBackground,
    onBackground = HcDarkOnSurface,
    surface = HcDarkSurface,
    onSurface = HcDarkOnSurface,
    surfaceVariant = HcDarkSurfaceVariant,
    onSurfaceVariant = HcDarkOnSurface,
    outline = HcDarkOutline,
    error = Color(0xFFFF4D4D),
    onError = Color.Black
)

private val CalibraShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun CalibraTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        highContrast && isDark -> HighContrastDarkScheme
        highContrast && !isDark -> HighContrastLightScheme
        isDark -> StandardDarkScheme
        else -> StandardLightScheme
    }

    val visualTokens = CalibraVisualTokens(
        laserPrimary = if (highContrast) LaserHighContrastYellow else ArLineWhite,
        laserSecondary = if (highContrast) Color(0xFF00FFCC) else ArAccentEmerald,
        laserWarning = if (highContrast) Color(0xFFFF5252) else ArAccentAmber,
        arNodeBlue = if (highContrast) LaserHighContrastYellow else ArNodeBlue,
        cardBorderWidth = if (highContrast) 2.dp else 1.dp,
        highContrast = highContrast
    )

    CompositionLocalProvider(LocalCalibraTokens provides visualTokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CalibraTypography,
            shapes = CalibraShapes,
            content = content
        )
    }
}
