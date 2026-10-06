package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportFormatterTest {

    @Test
    fun producesConciseFailureSummaryAndAggregatesSmartCenterOptions() {
        val session = "session-1"
        val operation = "op-1"
        val logs = listOf(
            event(
                time = 1_000L,
                sequence = "1",
                type = "MEDIA_VALIDATION_STARTED",
                level = DiagnosticLevel.INFO,
                reason = "بدء التحقق",
                session = session,
                operation = operation,
                context = mapOf("stage" to "MEDIA_VALIDATION", "platform" to "YouTube"),
            ),
            event(
                time = 2_000L,
                sequence = "2",
                type = "MEDIA_PROBE_ATTEMPT",
                level = DiagnosticLevel.WARNING,
                reason = "range_get_response",
                session = session,
                operation = operation,
                context = mapOf(
                    "status_code" to "403",
                    "content_type" to "text/plain",
                    "platform" to "YouTube",
                    "candidate_id" to "137",
                ),
            ),
            event(
                time = 3_000L,
                sequence = "3",
                type = "MEDIA_VALIDATION_REJECTED",
                level = DiagnosticLevel.WARNING,
                reason = "HTTP_403",
                session = session,
                operation = operation,
                context = mapOf(
                    "failure_code" to "HTTP_403",
                    "http_status" to "403",
                    "platform" to "YouTube",
                ),
            ),
            event(
                time = 3_500L,
                sequence = "4",
                type = "youtube.session_snapshot",
                level = DiagnosticLevel.INFO,
                reason = "snapshot",
                session = session,
                operation = operation,
                context = mapOf(
                    "platform" to "YouTube",
                    "browser_media_observed" to "0",
                    "browser_request_headers_captured" to "0",
                    "browser_po_token_observed" to "false",
                ),
            ),
            event(
                time = 4_000L,
                sequence = "5",
                type = "SMART_CENTER_OPTION_VISIBLE",
                level = DiagnosticLevel.INFO,
                reason = "visible",
                session = session,
                operation = operation,
            ),
            event(
                time = 5_000L,
                sequence = "6",
                type = "SMART_CENTER_OPTION_HIDDEN",
                level = DiagnosticLevel.INFO,
                reason = "hidden",
                session = session,
                operation = operation,
            ),
            event(
                time = 6_000L,
                sequence = "7",
                type = "SMART_CENTER_ERROR_VISIBLE",
                level = DiagnosticLevel.ERROR,
                reason = "ظهر خطأ للمستخدم",
                session = session,
                operation = operation,
            ),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("diagnostic_schema=2"))
        assertTrue(report.contains("version=0.1.1"))
        assertTrue(report.contains("build=debug"))
        assertTrue(report.contains("status=FAILED"))
        assertTrue(report.contains("classification=NETWORK"))
        assertTrue(report.contains("root_cause=HTTP_403"))
        assertTrue(report.contains("action=INSPECT_BROWSER_MEDIA_CAPTURE"))
        assertTrue(report.contains("http_403=1"))
        assertTrue(report.contains("failure=NO_VALID_MEDIA_SOURCE"))
        assertTrue(report.contains("duration_ms=5000"))
        assertTrue(report.contains("http_4xx=1"))
        assertTrue(report.contains("YOUTUBE"))
        assertTrue(report.contains("browser_media_observed=0"))
        assertTrue(report.contains("browser_po_token_observed=false"))
        assertTrue(report.contains("http_5xx=0"))
        assertTrue(report.contains("options_extracted=2"))
        assertTrue(report.contains("visible=1"))
        assertTrue(report.contains("hidden=1"))
        assertTrue(report.contains("resolver"))
        assertTrue(report.contains("candidate_rejected"))
        assertTrue(report.contains("ui_error"))
        assertEquals(0, Regex("SMART_CENTER_OPTION_VISIBLE").findAll(report).count())
    }

    @Test
    fun successfulSmartCenterUiFlowHasNoFalseRootCause() {
        val session = "session-ok"
        val operation = "op-ok"
        val logs = listOf(
            event(1_000L, "1", "SMART_CENTER_ORDERING", DiagnosticLevel.INFO, "تم تحديد ترتيب النتائج", session, operation),
            event(1_004L, "2", "SMART_CENTER_RESULT_PRESENTED", DiagnosticLevel.INFO, "تم عرض النتائج", session, operation),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("status=OK"))
        assertTrue(report.contains("classification=UI_FLOW"))
        assertTrue(report.contains("root_cause=NONE"))
        assertTrue(report.contains("failure=NONE"))
        assertTrue(report.contains("action=NONE"))
        assertTrue(report.contains("ordering=COMPLETED"))
        assertTrue(report.contains("presentation=COMPLETED"))
        assertTrue(report.contains("media_validation=NOT_STARTED"))
        assertTrue(report.contains("download=NOT_STARTED"))
        assertTrue(report.contains("duration_ms=4"))
        assertTrue(report.contains("FAILURE_CHAIN\nNONE"))
    }

    private fun event(
        time: Long,
        sequence: String,
        type: String,
        level: DiagnosticLevel,
        reason: String,
        session: String,
        operation: String,
        context: Map<String, String> = emptyMap(),
    ) = DiagnosticLog(
        id = sequence,
        timestampEpochMs = time,
        level = level,
        type = type,
        reason = reason,
        operation = "download.validate",
        context = context + mapOf(
            "app_package" to "com.ahdownload.app",
            "app_version_name" to "0.1.1",
            "app_version_code" to "2",
            "app_build_type" to "debug",
            "android_release" to "15",
            "android_sdk" to "35",
            "app_target_sdk" to "36",
            "device_manufacturer" to "samsung",
            "device_model" to "SM-G996W",
            "diagnostic_session_id" to session,
            "operation_id" to operation,
            "event_sequence" to sequence,
        ),
    )
}
