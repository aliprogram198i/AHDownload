package com.ahdownload.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shared AHDownload geometry.
 *
 * Small controls stay compact while content surfaces use a softer 16–24dp
 * radius. The hierarchy is intentionally restrained so the app does not feel
 * like a stack of unrelated floating cards.
 */
val AHShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)
