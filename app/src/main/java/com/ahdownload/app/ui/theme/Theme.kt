package com.ahdownload.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

private val LightColors = lightColorScheme(
    primary = Color(0xFF16324F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4ECF2),
    onPrimaryContainer = Color(0xFF10283E),
    secondary = Color(0xFF197C7A),
    secondaryContainer = Color(0xFFDDEEEB),
    onSecondaryContainer = Color(0xFF0E2D2B),
    tertiary = Color(0xFF4C6A88),
    background = Color(0xFFF7F5F0),
    surface = Color(0xFFF7F5F0),
    surfaceVariant = Color(0xFFE9E7E1),
    outline = Color(0xFF77756F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    onPrimary = Color(0xFF0C216F),
    primaryContainer = Color(0xFF1F357E),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF44465A),
    onSecondaryContainer = Color(0xFFE1E2F6),
    tertiary = Color(0xFF63DBC5),
    background = Color(0xFF101114),
    surface = Color(0xFF15171B),
    surfaceVariant = Color(0xFF282A30),
    outline = Color(0xFF90929B)
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
