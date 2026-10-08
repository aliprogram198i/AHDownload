package com.ahdownload.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAHThemeMode = staticCompositionLocalOf { AHThemeMode.SYSTEM }

enum class AHThemeMode(
    val storageValue: String,
    val label: String,
    val description: String,
) {
    SYSTEM("system", "النظام", "يتبع مظهر الجهاز"),
    LIGHT("light", "فاتح", "واجهة فاتحة دائمًا"),
    DARK("dark", "داكن", "واجهة داكنة متوازنة"),
    DARK_TECH("dark_tech", "Dark Tech", "Obsidian مع Cyan وIndigo"),
}

private val AhDarkColors = darkColorScheme(
    primary = Color(0xFF8B6DFF),
    onPrimary = Color(0xFF130F22),
    secondary = Color(0xFF43CBEA),
    onSecondary = Color(0xFF001117),
    tertiary = Color(0xFF48D9B4),
    background = Color(0xFF080B12),
    onBackground = Color(0xFFF2F4FA),
    surface = Color(0xFF101520),
    onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color(0xFF171E2A),
    onSurfaceVariant = Color(0xFFAAB4C3),
    outline = Color(0xFF303A4A),
    outlineVariant = Color(0xFF252E3C),
    error = Color(0xFFFF6B73),
    errorContainer = Color(0xFF401C22),
    onErrorContainer = Color(0xFFFFDAD9),
)

private val AhDarkTechColors = darkColorScheme(
    primary = Color(0xFF21D9FF),
    onPrimary = Color(0xFF001219),
    secondary = Color(0xFF7C5CFF),
    onSecondary = Color.White,
    tertiary = Color(0xFF20E6B2),
    background = Color(0xFF0B0F19),
    onBackground = Color(0xFFF2F7FF),
    surface = Color(0xFF101624),
    onSurface = Color(0xFFF2F7FF),
    surfaceVariant = Color(0xFF182232),
    onSurfaceVariant = Color(0xFFA9B6C7),
    outline = Color(0xFF344257),
    outlineVariant = Color(0xFF263244),
    error = Color(0xFFFF7580),
    errorContainer = Color(0xFF421A22),
    onErrorContainer = Color(0xFFFFDADB),
)

private val AhLightColors = lightColorScheme(
    primary = Color(0xFF5D35D8),
    onPrimary = Color.White,
    secondary = Color(0xFF006B85),
    onSecondary = Color.White,
    tertiary = Color(0xFF15876E),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF151823),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151823),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF626978),
    outline = Color(0xFFD8DCE6),
    outlineVariant = Color(0xFFE5E8EF),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun AHTheme(
    themeMode: AHThemeMode = AHThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val resolvedDark = when (themeMode) {
        AHThemeMode.SYSTEM -> systemDark
        AHThemeMode.LIGHT -> false
        AHThemeMode.DARK,
        AHThemeMode.DARK_TECH -> true
    }
    val colors = when {
        themeMode == AHThemeMode.DARK_TECH -> AhDarkTechColors
        resolvedDark -> AhDarkColors
        else -> AhLightColors
    }

    CompositionLocalProvider(LocalAHThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = colors,
            typography = AHTypography,
            shapes = AHShapes,
            content = content,
        )
    }
}
