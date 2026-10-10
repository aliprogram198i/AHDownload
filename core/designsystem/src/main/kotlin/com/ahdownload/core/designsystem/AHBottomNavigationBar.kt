package com.ahdownload.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

enum class AHBottomNavDestination {
    HOME,
    DOWNLOADS,
    SETTINGS,
}

@Composable
fun AHBottomNavigationBar(
    selected: AHBottomNavDestination,
    onDestinationSelected: (AHBottomNavDestination) -> Unit,
    activeDownloads: Int = 0,
    trackingScreen: String = "BOTTOM_NAVIGATION",
) {
    val diagnosticLogger = LocalDiagnosticUiTraceLogger.current
    NavigationBar(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(22.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = AHBottomNavigationDefaults.Elevation,
    ) {
        NavigationBarItem(
            selected = selected == AHBottomNavDestination.HOME,
            onClick = { dispatchDiagnosticAction(diagnosticLogger, trackingScreen, "nav.home", "الرئيسية") { onDestinationSelected(AHBottomNavDestination.HOME) } },
            modifier = diagnosticControlModifier(Modifier, trackingScreen, "nav.home", "الرئيسية", true),
            icon = { Icon(Icons.Rounded.Home, contentDescription = "الرئيسية") },
            label = { Text("الرئيسية") },
            colors = AHBottomNavigationDefaults.ItemColors,
        )
        NavigationBarItem(
            selected = selected == AHBottomNavDestination.DOWNLOADS,
            onClick = { dispatchDiagnosticAction(diagnosticLogger, trackingScreen, "nav.downloads", "التنزيلات") { onDestinationSelected(AHBottomNavDestination.DOWNLOADS) } },
            modifier = diagnosticControlModifier(Modifier, trackingScreen, "nav.downloads", "التنزيلات", true),
            icon = {
                if (activeDownloads > 0) {
                    BadgedBox(badge = {
                        Badge { Text(activeDownloads.coerceAtMost(99).toString()) }
                    }) {
                        Icon(Icons.Rounded.Download, contentDescription = "سجل التنزيلات")
                    }
                } else {
                    Icon(Icons.Rounded.Download, contentDescription = "سجل التنزيلات")
                }
            },
            label = { Text("التنزيلات") },
            colors = AHBottomNavigationDefaults.ItemColors,
        )
        NavigationBarItem(
            selected = selected == AHBottomNavDestination.SETTINGS,
            onClick = { dispatchDiagnosticAction(diagnosticLogger, trackingScreen, "nav.settings", "الإعدادات") { onDestinationSelected(AHBottomNavDestination.SETTINGS) } },
            modifier = diagnosticControlModifier(Modifier, trackingScreen, "nav.settings", "الإعدادات", true),
            icon = { Icon(Icons.Rounded.Settings, contentDescription = "الإعدادات") },
            label = { Text("الإعدادات") },
            colors = AHBottomNavigationDefaults.ItemColors,
        )
    }
}

private object AHBottomNavigationDefaults {
    val Elevation = 3.dp
    val ItemColors: androidx.compose.material3.NavigationBarItemColors
        @Composable get() = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}
