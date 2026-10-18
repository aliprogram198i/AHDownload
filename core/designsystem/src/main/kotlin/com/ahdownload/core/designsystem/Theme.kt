package com.ahdownload.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * AHDownload intentionally exposes one visual theme so screens remain consistent.
 * The enum/store contract is kept to avoid coupling app navigation to theme rendering.
 */
val LocalAHThemeMode = staticCompositionLocalOf { AHThemeMode.LIGHT }

enum class AHThemeMode(
    val storageValue: String,
    val label: String,
    val description: String,
) {
    LIGHT(
        storageValue = "light",
        label = "Modern Indigo Light",
        description = "واجهة فاتحة هادئة بلمسات نيلية واضحة وتباين مريح للقراءة",
    ),
}

private val ModernIndigoLightColors = lightColorScheme(
    primary = Color(0xFF5265C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EBFF),
    onPrimaryContainer = Color(0xFF232D65),
    secondary = Color(0xFF54799F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EEF7),
    onSecondaryContainer = Color(0xFF183248),
    tertiary = Color(0xFF247A61),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF4E8),
    onTertiaryContainer = Color(0xFF174634),
    background = Color(0xFFF6F7FC),
    onBackground = Color(0xFF252A41),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF252A41),
    surfaceVariant = Color(0xFFEEF0F8),
    onSurfaceVariant = Color(0xFF565E78),
    outline = Color(0xFFD6DCEC),
    outlineVariant = Color(0xFFE4E7F0),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun AHTheme(
    themeMode: AHThemeMode = AHThemeMode.LIGHT,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAHThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = ModernIndigoLightColors,
            typography = AHTypography,
            shapes = AHShapes,
            content = content,
        )
    }
}
