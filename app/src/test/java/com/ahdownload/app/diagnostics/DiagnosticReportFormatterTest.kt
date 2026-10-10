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
                    "candidate_id" to "137",
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

        assertTrue(report.contains("diagnostic_schema=3"))
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
    fun metricsAreScopedToFailingOperationAndCandidatesUseIds() {
        val session = "session-scope"
        val oldOperation = "op-old"
        val failingOperation = "op-fail"
        val logs = listOf(
            event(
                time = 1_000L, sequence = "1", type = "MEDIA_VALIDATION_STARTED",
                level = DiagnosticLevel.INFO, reason = "old", session = session, operation = oldOperation,
            ),
            event(
                time = 2_000L, sequence = "2", type = "MEDIA_PROBE_ATTEMPT",
                level = DiagnosticLevel.WARNING, reason = "old_403", session = session, operation = oldOperation,
                context = mapOf("status_code" to "403", "candidate_id" to "old"),
            ),
            event(
                time = 3_000L, sequence = "3", type = "MEDIA_VALIDATION_REJECTED",
                level = DiagnosticLevel.WARNING, reason = "HTTP_403", session = session, operation = oldOperation,
                context = mapOf("http_status" to "403", "candidate_id" to "old"),
            ),
            event(
                time = 4_000L, sequence = "4", type = "MEDIA_VALIDATION_STARTED",
                level = DiagnosticLevel.INFO, reason = "start", session = session, operation = failingOperation,
                context = mapOf("candidate_id" to "137"),
            ),
            event(
                time = 5_000L, sequence = "5", type = "MEDIA_PROBE_ATTEMPT",
                level = DiagnosticLevel.WARNING, reason = "403_a", session = session, operation = failingOperation,
                context = mapOf("status_code" to "403", "candidate_id" to "137"),
            ),
            event(
                time = 6_000L, sequence = "6", type = "MEDIA_PROBE_ATTEMPT",
                level = DiagnosticLevel.WARNING, reason = "403_b", session = session, operation = failingOperation,
                context = mapOf("status_code" to "403", "candidate_id" to "137"),
            ),
            event(
                time = 7_000L, sequence = "7", type = "MEDIA_VALIDATION_REJECTED",
                level = DiagnosticLevel.WARNING, reason = "HTTP_403", session = session, operation = failingOperation,
                context = mapOf("http_status" to "403", "candidate_id" to "137"),
            ),
            event(
                time = 8_000L, sequence = "8", type = "MEDIA_VALIDATION_REJECTED",
                level = DiagnosticLevel.WARNING, reason = "HTTP_403", session = session, operation = failingOperation,
                context = mapOf("http_status" to "403", "candidate_id" to "248"),
            ),
            event(
                time = 9_000L, sequence = "9", type = "DOWNLOAD_ERROR",
                level = DiagnosticLevel.ERROR, reason = "failed", session = session, operation = failingOperation,
            ),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("http_403=2"))
        assertTrue(report.contains("http_4xx=2"))
        assertTrue(report.contains("requests=2"))
        assertTrue(report.contains("candidates=2"))
        assertTrue(report.contains("rejected=2"))
    }

    @Test
    fun latestErrorWithoutOperationIdScopesToEventsSincePreviousError() {
        val session = "session-window"
        val logs = listOf(
            event(
                time = 1_000L, sequence = "1", type = "DOWNLOAD_ERROR",
                level = DiagnosticLevel.ERROR, reason = "old", session = session, operation = "old",
                context = mapOf("failure_code" to "old_failure"),
            ),
            event(
                time = 2_000L, sequence = "2", type = "DOWNLOAD_STARTED",
                level = DiagnosticLevel.INFO, reason = "new", session = session, operation = "new",
            ).copy(context = event(
                time = 2_000L, sequence = "2", type = "DOWNLOAD_STARTED",
                level = DiagnosticLevel.INFO, reason = "new", session = session, operation = "new",
            ).context - "operation_id"),
            event(
                time = 3_000L, sequence = "3", type = "HTTP_REQUEST",
                level = DiagnosticLevel.WARNING, reason = "403", session = session, operation = "new",
                context = mapOf("status_code" to "403"),
            ).copy(context = event(
                time = 3_000L, sequence = "3", type = "HTTP_REQUEST",
                level = DiagnosticLevel.WARNING, reason = "403", session = session, operation = "new",
                context = mapOf("status_code" to "403"),
            ).context - "operation_id"),
            event(
                time = 4_000L, sequence = "4", type = "DOWNLOAD",
                level = DiagnosticLevel.ERROR, reason = "new_failure", session = session, operation = "new",
            ).copy(context = event(
                time = 4_000L, sequence = "4", type = "DOWNLOAD",
                level = DiagnosticLevel.ERROR, reason = "new_failure", session = session, operation = "new",
            ).context - "operation_id"),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("error_type=DOWNLOAD"))
        assertTrue(report.contains("root_cause=HTTP_403"))
        assertTrue(report.contains("incident_events=3"))
        assertTrue(report.contains("status_code=403"))
        assertTrue(report.contains("new_failure"))
        assertTrue(!report.contains("old_failure"))
    }

    @Test
    fun youtubeGvsPolicyEvidenceGetsPreciseAction() {
        val session = "session-gvs"
        val operation = "op-gvs"
        val logs = listOf(
            event(1_000L, "1", "MEDIA_PROBE_ATTEMPT", DiagnosticLevel.WARNING, "403", session, operation,
                mapOf("status_code" to "403", "candidate_id" to "137", "platform" to "YouTube")),
            event(1_100L, "2", "youtube.gvs_strategy", DiagnosticLevel.WARNING, "browser_gvs_media_observed_without_po_token", session, operation,
                mapOf(
                    "platform" to "YouTube",
                    "browser_media_observed" to "3",
                    "browser_request_headers_captured" to "3",
                    "po_token_observed" to "false",
                )),
            event(1_200L, "3", "MEDIA_VALIDATION_REJECTED", DiagnosticLevel.ERROR, "HTTP_403", session, operation,
                mapOf("http_status" to "403", "failure_code" to "HTTP_403", "candidate_id" to "137", "platform" to "YouTube")),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("root_cause=HTTP_403"))
        assertTrue(report.contains("browser_media_observed=3"))
        assertTrue(report.contains("po_token_observed=false"))
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

    @Test
    fun latestAudioExtractionFailureOverridesEarlierHttp403() {
        val session = "session-audio"
        val operation = "op-audio"
        val logs = listOf(
            event(
                time = 1_000L,
                sequence = "1",
                type = "MEDIA_PROBE_ATTEMPT",
                level = DiagnosticLevel.WARNING,
                reason = "HTTP_403",
                session = session,
                operation = operation,
                context = mapOf("status_code" to "403", "candidate_id" to "137"),
            ),
            event(
                time = 2_000L,
                sequence = "2",
                type = "MEDIA_VALIDATION_ACCEPTED",
                level = DiagnosticLevel.INFO,
                reason = "valid",
                session = session,
                operation = operation,
                context = mapOf("candidate_id" to "android-18"),
            ),
            event(
                time = 3_000L,
                sequence = "3",
                type = "AUDIO_EXTRACTION_FAILED",
                level = DiagnosticLevel.ERROR,
                reason = "FFmpeg runtime failure",
                session = session,
                operation = "download.audio_extraction",
                context = mapOf(
                    "stage" to "DOWNLOAD",
                    "output_format" to "Mp3",
                ),
            ),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("classification=AUDIO_PROCESSING"))
        assertTrue(report.contains("root_cause=AUDIO_EXTRACTION_FAILED"))
        assertTrue(report.contains("failure=AUDIO_EXTRACTION_FAILED"))
        assertTrue(report.contains("action=INSPECT_AUDIO_PROCESSOR"))
    }

    @Test
    fun destinationCopyFailureReportsStorageStageAfterSuccessfulValidationAndExtraction() {
        val session = "session-storage"
        val operation = "op-storage"
        val logs = listOf(
            event(
                time = 1_000L,
                sequence = "1",
                type = "MEDIA_VALIDATION_ACCEPTED",
                level = DiagnosticLevel.INFO,
                reason = "valid",
                session = session,
                operation = operation,
                context = mapOf("candidate_id" to "instagram-video"),
            ),
            event(
                time = 2_000L,
                sequence = "2",
                type = "DOWNLOAD_STARTED",
                level = DiagnosticLevel.INFO,
                reason = "started",
                session = session,
                operation = operation,
            ),
            event(
                time = 3_000L,
                sequence = "3",
                type = "AUDIO_EXTRACTION_COMPLETED",
                level = DiagnosticLevel.INFO,
                reason = "audio ready",
                session = session,
                operation = operation,
            ),
            event(
                time = 4_000L,
                sequence = "4",
                type = "DOWNLOAD_DESTINATION_COPY_FAILED",
                level = DiagnosticLevel.ERROR,
                reason = "Invalid URI",
                session = session,
                operation = operation,
                context = mapOf("destination_mode" to "CUSTOM_DIRECTORY"),
            ),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("status=FAILED"))
        assertTrue(report.contains("classification=STORAGE"))
        assertTrue(report.contains("root_cause=STORAGE_ERROR"))
        assertTrue(report.contains("action=INSPECT_STORAGE"))
        assertTrue(report.contains("download=COMPLETED"))
        assertTrue(
            report.contains(
                "FAILURE_CHAIN\nvalidation_passed -> download_completed_locally -> " +
                    "audio_extraction_completed -> destination_copy_failed",
            ),
        )
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

    @Test
    fun classifiesYouTubeAgeGateAsAuthenticationNotResolverOrNetworkFailure() {
        val session = "session-age-gate"
        val operation = "op-age-gate"
        val logs = listOf(
            event(
                time = 1_000L,
                sequence = "1",
                type = "ANALYSIS_STARTED",
                level = DiagnosticLevel.INFO,
                reason = "started",
                session = session,
                operation = operation,
                context = mapOf("platform" to "YouTube"),
            ).copy(operation = "home.analyze"),
            event(
                time = 2_000L,
                sequence = "2",
                type = "youtube.authentication_required",
                level = DiagnosticLevel.WARNING,
                reason = "age_restricted_or_sign_in_required_without_authenticated_session",
                session = session,
                operation = operation,
                context = mapOf(
                    "platform" to "YouTube",
                    "authenticated" to "false",
                    "failure_code" to "AuthenticationRequired",
                    "browser_media_observed" to "0",
                ),
            ).copy(operation = "youtube.resolve"),
            event(
                time = 3_000L,
                sequence = "3",
                type = "AUTH_REQUIRED",
                level = DiagnosticLevel.ERROR,
                reason = "AuthenticationRequired",
                session = session,
                operation = operation,
                context = mapOf(
                    "platform" to "YouTube",
                    "failure_code" to "AuthenticationRequired",
                    "reason" to "يتطلب هذا الفيديو تسجيل الدخول إلى YouTube وتأكيد الأهلية العمرية.",
                ),
            ).copy(operation = "home.resolve"),
        )

        val report = DiagnosticReportFormatter.format(logs)

        assertTrue(report.contains("status=FAILED"))
        assertTrue(report.contains("classification=AUTHENTICATION"))
        assertTrue(report.contains("root_cause=AUTHENTICATION_REQUIRED"))
        assertTrue(report.contains("action=COMPLETE_YOUTUBE_AGE_VERIFICATION"))
        assertTrue(report.contains("resolution=FAILED"))
        assertTrue(report.contains("failure=AUTHENTICATION_REQUIRED"))
        assertTrue(report.contains("FAILURE_CHAIN\nresolver -> authentication_required"))
        assertTrue(!report.contains("classification=NETWORK"))
        assertTrue(!report.contains("action=INSPECT_RESOLVER"))
    }

}
