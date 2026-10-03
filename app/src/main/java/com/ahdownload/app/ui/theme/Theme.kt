package com.ahdownload.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

private val LightColors = lightColorScheme(
    primary = Color(0xFF3658D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF001452),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE1E2F6),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF006B5B),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFE7E8F0),
    outline = Color(0xFF777985)
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
