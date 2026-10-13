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
    primary = Color(0xFF5746D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEEBFF),
    onPrimaryContainer = Color(0xFF211750),
    secondary = Color(0xFF2E78B7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1F1FF),
    onSecondaryContainer = Color(0xFF143650),
    tertiary = Color(0xFF187F5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7F4E5),
    onTertiaryContainer = Color(0xFF103D2B),
    background = Color(0xFFF7F8FE),
    onBackground = Color(0xFF25213B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF25213B),
    surfaceVariant = Color(0xFFF1F0FA),
    onSurfaceVariant = Color(0xFF605D77),
    outline = Color(0xFFDAD9EA),
    outlineVariant = Color(0xFFE7E6F1),
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
