package com.ahdownload.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EEFF),
    onPrimaryContainer = Color(0xFF06245F),
    secondary = Color(0xFF00A896),
    secondaryContainer = Color(0xFFDDF7F3),
    onSecondaryContainer = Color(0xFF003D37),
    tertiary = Color(0xFF6750A4),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F7),
    outline = Color(0xFF7A8494)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF002E73),
    primaryContainer = Color(0xFF173B78),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF5FE0CF),
    secondaryContainer = Color(0xFF164B47),
    onSecondaryContainer = Color(0xFFBFF4EB),
    tertiary = Color(0xFFD0BCFF),
    background = Color(0xFF0A0E14),
    surface = Color(0xFF10151D),
    surfaceVariant = Color(0xFF1C2430),
    outline = Color(0xFF9AA8BB)
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun AHDownloadTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
