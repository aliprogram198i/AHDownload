package com.ahdownload.app.ui.theme

import com.ahdownload.app.ui.DesignAudit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val AHBrandGradient = Brush.linearGradient(listOf(Color(0xFF315BFF), Color(0xFF7B4DFF), Color(0xFF16B8B1)))
val AHBrandGradientSoft = Brush.linearGradient(listOf(Color(0xFF151B2D), Color(0xFF1B1730), Color(0xFF102523)))

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
    primary = Color(0xFF6F8BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF202D62),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF35D2C5),
    onSecondary = Color(0xFF062522),
    secondaryContainer = Color(0xFF123E3B),
    onSecondaryContainer = Color(0xFFA9F3EC),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF24113F),
    tertiaryContainer = Color(0xFF3A2864),
    onTertiaryContainer = Color(0xFFE8DDFF),
    background = Color(0xFF080A10),
    onBackground = Color(0xFFF2F4FA),
    surface = Color(0xFF0F121A),
    onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color(0xFF171B24),
    onSurfaceVariant = Color(0xFF9AA2B3),
    outline = Color(0xFF626A7A),
    outlineVariant = Color(0xFF292F3B),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
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

@Composable
fun AHDownloadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
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
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    DesignAudit.recordComponent("AHGradientButton")
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) AHBrandGradient else MaterialTheme.colorScheme.surfaceVariant)
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
