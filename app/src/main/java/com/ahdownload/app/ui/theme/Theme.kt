package com.ahdownload.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF3157D5),
    secondary = androidx.compose.ui.graphics.Color(0xFF5B5FC7),
    tertiary = androidx.compose.ui.graphics.Color(0xFF006B5B)
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB7C4FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFC4C5FF),
    tertiary = androidx.compose.ui.graphics.Color(0xFF63DBC5)
)

@Composable
fun AHDownloadTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography(), content = content)
}
