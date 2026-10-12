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
    SYSTEM("system", "حسب الجهاز", "يتبع مظهر الجهاز"),
    LIGHT("light", "Modern Indigo Light", "واجهة فاتحة بخلفيات هادئة ولمسات بنفسجية نيلية"),
    DARK("dark", "Indigo Dark", "واجهة داكنة بألوان نيلية وتباين مريح"),
    DARK_TECH("dark_tech", "Dark Tech", "فحمي عميق مع لمسات نيلية وسماوية"),
}

private val AhDarkColors = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF21145A),
    primaryContainer = Color(0xFF382F76),
    onPrimaryContainer = Color(0xFFE9E5FF),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF062B3A),
    secondaryContainer = Color(0xFF0E3D50),
    onSecondaryContainer = Color(0xFFC3F1FF),
    tertiary = Color(0xFF83D5A4),
    onTertiary = Color(0xFF092918),
    tertiaryContainer = Color(0xFF17452D),
    onTertiaryContainer = Color(0xFFC0F1D0),
    background = Color(0xFF10111A),
    onBackground = Color(0xFFF3F1FF),
    surface = Color(0xFF171826),
    onSurface = Color(0xFFF3F1FF),
    surfaceVariant = Color(0xFF24263A),
    onSurfaceVariant = Color(0xFFC1C3DA),
    outline = Color(0xFF4A4C68),
    outlineVariant = Color(0xFF34364D),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AhDarkTechColors = darkColorScheme(
    primary = Color(0xFF9B8CFF),
    onPrimary = Color(0xFF1B124D),
    primaryContainer = Color(0xFF30265F),
    onPrimaryContainer = Color(0xFFE5DFFF),
    secondary = Color(0xFF5BE1F2),
    onSecondary = Color(0xFF062B32),
    secondaryContainer = Color(0xFF103A42),
    onSecondaryContainer = Color(0xFFB8F7FF),
    tertiary = Color(0xFF8CE7B6),
    onTertiary = Color(0xFF063821),
    tertiaryContainer = Color(0xFF145234),
    onTertiaryContainer = Color(0xFFC7F8D8),
    background = Color(0xFF0B0F19),
    onBackground = Color(0xFFF4F7FF),
    surface = Color(0xFF101625),
    onSurface = Color(0xFFF4F7FF),
    surfaceVariant = Color(0xFF1A2235),
    onSurfaceVariant = Color(0xFFADB7CE),
    outline = Color(0xFF39445B),
    outlineVariant = Color(0xFF273146),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AhLightColors = lightColorScheme(
    primary = Color(0xFF5B45D5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEEAFE),
    onPrimaryContainer = Color(0xFF24165E),
    secondary = Color(0xFF006B85),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5F5FC),
    onSecondaryContainer = Color(0xFF003642),
    tertiary = Color(0xFF16845B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4F5E3),
    onTertiaryContainer = Color(0xFF073D29),
    background = Color(0xFFF5F6FC),
    onBackground = Color(0xFF25213B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF25213B),
    surfaceVariant = Color(0xFFF0EFF8),
    onSurfaceVariant = Color(0xFF625F78),
    outline = Color(0xFFD9D8E8),
    outlineVariant = Color(0xFFE7E5F1),
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
