package com.ahdownload.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AhDarkColors = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color.White,
    secondary = Color(0xFF46D6FF),
    onSecondary = Color(0xFF001017),
    tertiary = Color(0xFFFF6FB7),
    background = Color(0xFF080A12),
    onBackground = Color(0xFFF4F5FA),
    surface = Color(0xFF10131D),
    onSurface = Color(0xFFF4F5FA),
    surfaceVariant = Color(0xFF1A1F2B),
    onSurfaceVariant = Color(0xFFB7BECC),
    outline = Color(0xFF343B4A),
)

private val AhLightColors = lightColorScheme(
    primary = Color(0xFF5D35D8),
    onPrimary = Color.White,
    secondary = Color(0xFF006B85),
    onSecondary = Color.White,
    tertiary = Color(0xFFB32663),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF161922),
    surface = Color.White,
    onSurface = Color(0xFF161922),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF5D6472),
    outline = Color(0xFFD4D8E2),
)

@Composable
fun AHTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AhDarkColors else AhLightColors,
        typography = AHTypography,
        shapes = AHShapes,
        content = content,
    )
}
