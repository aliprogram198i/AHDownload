package com.ahdownload.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shared geometry for the Modern Indigo Light design system.
 * Compact controls and slightly softened content surfaces keep dense media
 * screens clear without making every element look like a floating card.
 */
val AHShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)
