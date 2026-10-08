package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTraceReportFormatterTest {
    @Test
    fun homeReportContainsUiAndPipelineTimeline() {
        val snapshot = UiTraceEvent(
            id = "1",
            timestampEpochMs = 1_000,
            sessionId = "home-session",
            sequence = 1,
            screen = "HOME",
            component = "HomeScreen",
            event = "UI_SNAPSHOT",
            state = "VISIBLE",
            context = mapOf(
                "app_package" to "com.ahdownload.app",
                "app_version_name" to "1.4.6",
                "app_version_code" to "28",
                "app_build_type" to "release",
                "android_release" to "15",
                "android_sdk" to "35",
                "app_target_sdk" to "36",
                "device_manufacturer" to "Samsung",
                "device_model" to "SM-G996W",
                "viewport_width_px" to "1080",
                "viewport_height_px" to "2400",
                "viewport_width_dp" to "384",
                "viewport_height_dp" to "853",
                "density" to "2.8125",
                "font_scale" to "1.0",
                "orientation" to "portrait",
                "theme_mode" to "dark_tech",
                "layout_direction" to "RTL",
                "locale" to "ar-EG",
                "components" to "topbar,url_input,analyze_button",
                "state_summary" to "url_empty=false;analyzing=false;resolving=true;platform=Instagram",
            ),
        )
        val diagnostic = UiTraceEvent(
            id = "2",
            timestampEpochMs = 2_000,
            sessionId = "home-session",
            sequence = 2,
            screen = "HOME",
            component = "pipeline",
            event = "HOME_DIAGNOSTIC",
            state = "ERROR",
            level = DiagnosticLevel.ERROR,
            context = mapOf(
                "diagnostic_type" to "MEDIA_VALIDATION",
                "diagnostic_reason" to "HTTP_403",
                "diagnostic_operation" to "download.validate",
                "diag_operation_id" to "op-1",
                "diag_platform" to "Instagram",
                "diag_candidate_id" to "candidate-1",
            ),
        )

        val report = HomeTraceReportFormatter.format(listOf(snapshot, diagnostic))

        assertTrue(report.contains("AHDownload Home Screen Trace"))
        assertTrue(report.contains("CURRENT_STATE"))
        assertTrue(report.contains("DIAGNOSTICS"))
        assertTrue(report.contains("MEDIA_VALIDATION"))
        assertTrue(report.contains("download.validate"))
        assertTrue(report.contains("theme=dark_tech"))
        assertFalse(report.contains("watch?v="))
    }
}
