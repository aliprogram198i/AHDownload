package com.ahdownload.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

@Composable
fun rememberUiTraceContext(): Map<String, String> {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val darkTheme = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    return mapOf(
        "viewport_width_dp" to configuration.screenWidthDp.toString(),
        "viewport_height_dp" to configuration.screenHeightDp.toString(),
        "viewport_width_px" to (configuration.screenWidthDp * density.density).roundToInt().toString(),
        "viewport_height_px" to (configuration.screenHeightDp * density.density).roundToInt().toString(),
        "density" to density.density.toString(),
        "font_scale" to configuration.fontScale.toString(),
        "orientation" to when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "portrait"
        },
        "theme_mode" to if (darkTheme) "dark" else "light",
        "layout_direction" to when (LayoutDirectionAmbient.current) {
            LayoutDirection.Rtl -> "RTL"
            else -> "LTR"
        },
        "design_primary" to colors.primary.toHex(),
        "design_on_primary" to colors.onPrimary.toHex(),
        "design_secondary" to colors.secondary.toHex(),
        "design_on_secondary" to colors.onSecondary.toHex(),
        "design_tertiary" to colors.tertiary.toHex(),
        "design_background" to colors.background.toHex(),
        "design_on_background" to colors.onBackground.toHex(),
        "design_surface" to colors.surface.toHex(),
        "design_on_surface" to colors.onSurface.toHex(),
        "design_surface_variant" to colors.surfaceVariant.toHex(),
        "design_on_surface_variant" to colors.onSurfaceVariant.toHex(),
        "design_outline" to colors.outline.toHex(),
        "typography_display_large_sp" to typography.displayLarge.fontSize.value.toString(),
        "typography_headline_large_sp" to typography.headlineLarge.fontSize.value.toString(),
        "typography_headline_medium_sp" to typography.headlineMedium.fontSize.value.toString(),
        "typography_title_large_sp" to typography.titleLarge.fontSize.value.toString(),
        "typography_body_large_sp" to typography.bodyLarge.fontSize.value.toString(),
        "typography_body_medium_sp" to typography.bodyMedium.fontSize.value.toString(),
        "shape_small_dp" to "12",
        "shape_medium_dp" to "20",
        "shape_large_dp" to "28",
        "design_spacing_unit_dp" to "4",
    )
}

private object LayoutDirectionAmbient {
    @Composable
    val current: LayoutDirection
        get() = androidx.compose.ui.unit.LayoutDirection.Ltr
}

private fun Color.toHex(): String = buildString {
    append('#')
    append((value shr 16 and 0xFF).toString(16).padStart(2, '0'))
    append((value shr 8 and 0xFF).toString(16).padStart(2, '0'))
    append((value and 0xFF).toString(16).padStart(2, '0'))
}