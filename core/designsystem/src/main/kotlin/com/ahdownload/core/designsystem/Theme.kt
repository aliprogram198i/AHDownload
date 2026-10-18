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
    primary = Color(0xFF5367D8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7EBFF),
    onPrimaryContainer = Color(0xFF1E2A66),
    secondary = Color(0xFF8491E8),
    onSecondary = Color(0xFF1F285E),
    secondaryContainer = Color(0xFFE9ECFF),
    onSecondaryContainer = Color(0xFF27316A),
    tertiary = Color(0xFF218C70),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9F2E9),
    onTertiaryContainer = Color(0xFF124B3C),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF252A41),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF252A41),
    surfaceVariant = Color(0xFFEEF0F8),
    onSurfaceVariant = Color(0xFF565E78),
    outline = Color(0xFFD4D9EA),
    outlineVariant = Color(0xFFE3E7F2),
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
