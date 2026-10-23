package com.ahdownload.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The single Modern Indigo Light theme is the visual source of truth for all app surfaces.
 * Keep the enum/storage contract stable so saved appearance preferences remain compatible.
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
        description = "واجهة فاتحة بإنديغو حديث، ومساحات نظيفة، وتباين مريح للقراءة",
    ),
}

private val ModernIndigoLightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF26215F),
    secondary = Color(0xFF5B55E7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E9FF),
    onSecondaryContainer = Color(0xFF2D2E72),
    tertiary = Color(0xFF087F72),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5F5EF),
    onTertiaryContainer = Color(0xFF104E46),
    background = Color(0xFFF7F8FE),
    onBackground = Color(0xFF202442),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202442),
    surfaceVariant = Color(0xFFF0F1FB),
    onSurfaceVariant = Color(0xFF535A76),
    outline = Color(0xFFCDD2E8),
    outlineVariant = Color(0xFFE1E5F2),
    error = Color(0xFFB42318),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
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
