package com.ahdownload.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * AHDownload exposes one carefully tuned light theme to keep every screen consistent.
 * Keep the enum/storage contract stable so appearance preferences remain backwards compatible.
 */
val LocalAHThemeMode = staticCompositionLocalOf { AHThemeMode.LIGHT }

enum class AHThemeMode(
    val storageValue: String,
    val label: String,
    val description: String,
) {
    LIGHT(
        storageValue = "light",
        label = "Crystal Blue",
        description = "واجهة فاتحة ونظيفة بأزرق بلوري مريح وتباين واضح للقراءة",
    ),
}

private val CrystalBlueColors = lightColorScheme(
    primary = Color(0xFF245ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEAFF),
    onPrimaryContainer = Color(0xFF163A70),
    secondary = Color(0xFF3072D5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4F0FF),
    onSecondaryContainer = Color(0xFF183B69),
    tertiary = Color(0xFF16836C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9F4EC),
    onTertiaryContainer = Color(0xFF0F4A3E),
    background = Color(0xFFF4F8FF),
    onBackground = Color(0xFF1B2A41),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2A41),
    surfaceVariant = Color(0xFFEAF1FB),
    onSurfaceVariant = Color(0xFF4E5E76),
    outline = Color(0xFFCAD7E8),
    outlineVariant = Color(0xFFDEE7F3),
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
            colorScheme = CrystalBlueColors,
            typography = AHTypography,
            shapes = AHShapes,
            content = content,
        )
    }
}
