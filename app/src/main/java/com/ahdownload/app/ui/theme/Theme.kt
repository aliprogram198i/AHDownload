package com.ahdownload.app.ui.theme

import com.ahdownload.app.ui.DesignAudit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val AHBrandGradient = Brush.linearGradient(listOf(Color(0xFF4C6FFF), Color(0xFF8B5CF6), Color(0xFF20C7B7)))
val AHBrandGradientSoft = Brush.linearGradient(listOf(Color(0xFF18203A), Color(0xFF211A35), Color(0xFF122C2B)))

private val LightColors = lightColorScheme(
    primary = Color(0xFF315BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7ECFF),
    onPrimaryContainer = Color(0xFF0A2B72),
    secondary = Color(0xFF0B9F9A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F2EF),
    onSecondaryContainer = Color(0xFF073B39),
    tertiary = Color(0xFF7B4DFF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF28104E),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF171A20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF171A20),
    surfaceVariant = Color(0xFFE9EDF4),
    onSurfaceVariant = Color(0xFF454A54),
    outline = Color(0xFF747985),
    outlineVariant = Color(0xFFD0D5DE),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6E8BFF),
    onPrimary = Color(0xFF07102C),
    primaryContainer = Color(0xFF1B2858),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFF32D6C5),
    onSecondary = Color(0xFF06201D),
    secondaryContainer = Color(0xFF123E3A),
    onSecondaryContainer = Color(0xFFB8FFF7),
    tertiary = Color(0xFFAA83FF),
    onTertiary = Color(0xFF1B0D36),
    tertiaryContainer = Color(0xFF35205F),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF080A10),
    onBackground = Color(0xFFF4F7FF),
    surface = Color(0xFF10131B),
    onSurface = Color(0xFFF4F7FF),
    surfaceVariant = Color(0xFF161A24),
    onSurfaceVariant = Color(0xFF9AA3B5),
    outline = Color(0xFF303746),
    outlineVariant = Color(0xFF252B38),
    error = Color(0xFFFF8A9B),
    errorContainer = Color(0xFF3A1720),
    onErrorContainer = Color(0xFFFFD9DF)
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

enum class AHThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun AHDownloadTheme(
    themeMode: AHThemeMode = AHThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AHThemeMode.SYSTEM -> systemDark
        AHThemeMode.LIGHT -> false
        AHThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}


@Composable
fun AHGradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    DesignAudit.recordComponent("AHGradientButton")
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.background(AHBrandGradient)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                content = content
            )
        }
    }
}

@Composable
fun AHGradientOutlinedButton(
    onClick: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    DesignAudit.recordComponent("AHGradientOutlinedButton")
    val shape = RoundedCornerShape(14.dp)
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(shape)
            .background(AHBrandGradientSoft)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            content = content
        )
    }
}
