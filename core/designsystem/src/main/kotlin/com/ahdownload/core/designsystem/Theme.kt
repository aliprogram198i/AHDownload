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
    DARK("dark", "داكن", "واجهة داكنة بلمسة برتقالية"),
    DARK_TECH("dark_tech", "Orange Forge", "أسود فحمي وبرتقالي واضح"),
}

private val AhDarkColors = darkColorScheme(
    primary = Color(0xFFFF791A),
    onPrimary = Color(0xFF211006),
    primaryContainer = Color(0xFF4A2815),
    onPrimaryContainer = Color(0xFFFFDCC5),
    secondary = Color(0xFFE8A16A),
    onSecondary = Color(0xFF211006),
    secondaryContainer = Color(0xFF3B2A20),
    onSecondaryContainer = Color(0xFFFFE1CE),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    background = Color(0xFF111216),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF1B1C21),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF292A31),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF45464F),
    outlineVariant = Color(0xFF34353C),
    error = Color(0xFFFF6B73),
    errorContainer = Color(0xFF401C22),
    onErrorContainer = Color(0xFFFFDAD9),
)

private val AhDarkTechColors = darkColorScheme(
    primary = Color(0xFFFF791A),
    onPrimary = Color(0xFF211006),
    primaryContainer = Color(0xFF4A2815),
    onPrimaryContainer = Color(0xFFFFDCC5),
    secondary = Color(0xFFFFA34D),
    onSecondary = Color(0xFF211006),
    secondaryContainer = Color(0xFF3D281A),
    onSecondaryContainer = Color(0xFFFFDCC5),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF222225),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF2D2D32),
    error = Color(0xFFFF7580),
    errorContainer = Color(0xFF421A22),
    onErrorContainer = Color(0xFFFFDADB),
)

private val AhLightColors = lightColorScheme(
    primary = Color(0xFFC84E00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF321300),
    secondary = Color(0xFFB94700),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3D2),
    onSecondaryContainer = Color(0xFF321300),
    tertiary = Color(0xFF16803D),
    onTertiary = Color.White,
    background = Color(0xFFFAF8F6),
    onBackground = Color(0xFF1C1917),
    surface = Color(0xFFFFFEFC),
    onSurface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFFF3ECE6),
    onSurfaceVariant = Color(0xFF62564E),
    outline = Color(0xFFD7CBC1),
    outlineVariant = Color(0xFFE8DDD4),
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
