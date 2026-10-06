package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
                context = mapOf("stage" to "MEDIA_VALIDATION"),
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
                ),
            ),
            event(
                time = 4_000L,
                sequence = "4",
                type = "SMART_CENTER_OPTION_VISIBLE",
                level = DiagnosticLevel.INFO,
                reason = "visible",
                session = session,
                operation = operation,
            ),
            event(
                time = 5_000L,
                sequence = "5",
                type = "SMART_CENTER_OPTION_HIDDEN",
                level = DiagnosticLevel.INFO,
                reason = "hidden",
                session = session,
                operation = operation,
            ),
            event(
                time = 6_000L,
                sequence = "6",
                type = "SMART_CENTER_ERROR_VISIBLE",
                level = DiagnosticLevel.ERROR,
                reason = "ظهر خطأ للمستخدم",
                session = session,
                operation = operation,
            ),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertContains(report, "version=0.1.1")
        assertContains(report, "build=debug")
        assertContains(report, "status=FAILED")
        assertContains(report, "root_cause=HTTP_403")
        assertContains(report, "failure=NO_VALID_MEDIA_SOURCE")
        assertContains(report, "duration_ms=5000")
        assertContains(report, "options_extracted=2")
        assertContains(report, "visible=1")
        assertContains(report, "hidden=1")
        assertContains(report, "resolver")
        assertContains(report, "candidate_rejected")
        assertContains(report, "ui_error")
        assertEquals(1, Regex("SMART_CENTER_OPTION_VISIBLE").findAll(report).count())
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
