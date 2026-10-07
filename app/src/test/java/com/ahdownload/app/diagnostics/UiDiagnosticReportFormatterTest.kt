package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDiagnosticReportFormatterTest {
    @Test
    fun reportContainsUiStructureAndTimeline() {
        val event = UiTraceEvent(
            id = "1",
            timestampEpochMs = 1_000,
            sessionId = "session-1",
            sequence = 1,
            screen = "HOME",
            component = "HomeScreen",
            event = "UI_SNAPSHOT",
            state = "VISIBLE",
            context = mapOf(
                "app_package" to "com.ahdownload.app",
                "app_version_name" to "1.0.0",
                "app_version_code" to "10",
                "app_build_type" to "debug",
                "android_release" to "15",
                "android_sdk" to "35",
                "app_target_sdk" to "36",
                "device_manufacturer" to "Samsung",
                "device_model" to "SM-G996W",
                "viewport_width_px" to "1080",
                "viewport_height_px" to "2400",
                "density" to "3.0",
                "font_scale" to "1.0",
                "orientation" to "portrait",
                "theme_mode" to "dark",
                "layout_direction" to "RTL",
                "locale" to "ar",
                "components" to "topbar,url_input,analyze_button",
                "state_summary" to "ready=true",
                "design_primary" to "#5D35D8",
                "design_secondary" to "#006B85",
                "design_surface" to "#FFFFFF",
                "design_background" to "#F7F8FC",
                "design_outline" to "#D4D8E2",
            ),
        )

        val report = UiDiagnosticReportFormatter.format(listOf(event))

        assertTrue(report.contains("AHDownload UI Diagnostic"))
        assertTrue(report.contains("UI_STATE"))
        assertTrue(report.contains("DESIGN"))
        assertTrue(report.contains("topbar,url_input,analyze_button"))
        assertTrue(report.contains("HOME | HomeScreen | UI_SNAPSHOT"))
    }
}