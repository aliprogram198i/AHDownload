package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardTraceReportFormatterTest {
    @Test
    fun reportExplainsIncompleteResultAndKeepsLatestCandidateMetadata() {
        val oldGeneration = event(
            sequence = 1,
            time = 1_000,
            generation = "old-result",
            event = "SOURCE_CANDIDATE",
            context = mapOf("candidate_id" to "old-source", "height" to "720"),
        )
        val snapshot = event(
            sequence = 2,
            time = 2_000,
            generation = "new-result",
            event = "RESULT_SNAPSHOT",
            state = "INCOMPLETE",
            context = mapOf(
                "platform" to "YouTube",
                "raw_candidate_count" to "3",
                "deduplicated_primary_option_count" to "1",
                "video_picker_option_count" to "0",
                "audio_extraction_available" to "false",
                "unresolved_video_source_count" to "1",
                "no_auto_selection" to "true",
            ),
        )
        val source = event(
            sequence = 3,
            time = 3_000,
            generation = "new-result",
            event = "SOURCE_CANDIDATE",
            state = "BLOCKED_AUDIO_TRACK_UNCONFIRMED",
            context = mapOf(
                "candidate_id" to "browser-video-1",
                "media_kind" to "Video",
                "height" to "unknown",
                "has_audio" to "false",
                "source_url" to "https://private.example/stream?token=do-not-export",
                "authorization" to "Bearer do-not-export",
            ),
        )
        val state = event(
            sequence = 4,
            time = 4_000,
            generation = "new-result",
            event = "CARD_STATE",
            state = "IDLE",
            context = mapOf("can_download" to "false", "selected_video_candidate_id" to "none"),
        )

        val report = ResultCardTraceReportFormatter.format(listOf(oldGeneration, snapshot, source, state))

        assertTrue(report.contains("AHDownload Result Card Trace"))
        assertTrue(report.contains("raw_source_candidates=1"))
        assertTrue(report.contains("raw_candidate_count=3"))
        assertTrue(report.contains("video_picker_option_count=0"))
        assertTrue(report.contains("BLOCKED_AUDIO_TRACK_UNCONFIRMED"))
        assertTrue(report.contains("browser-video-1"))
        assertFalse(report.contains("old-source"))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("do-not-export"))
        assertTrue(report.contains("no_auto_selection=true"))
    }

    @Test
    fun reportContainsSanitizedFailureCategory() {
        val error = event(
            sequence = 1,
            time = 10_000,
            generation = "generation-1",
            event = "RESULT_CARD_ERROR",
            state = "ERROR",
            level = DiagnosticLevel.ERROR,
            context = mapOf(
                "error_category" to "HTTP_403",
                "error_summary" to "HTTP 403 from https://secret.example/video?token=sensitive",
            ),
        )

        val report = ResultCardTraceReportFormatter.format(listOf(error))

        assertTrue(report.contains("ERRORS"))
        assertTrue(report.contains("HTTP_403"))
        assertFalse(report.contains("secret.example"))
        assertFalse(report.contains("token=sensitive"))
    }

    @Test
    fun emptyHistoryReturnsExplicitStatus() {
        val report = ResultCardTraceReportFormatter.format(emptyList())

        assertTrue(report.contains("status=NO_RESULT_CARD_EVENTS"))
    }

    private fun event(
        sequence: Long,
        time: Long,
        generation: String,
        event: String,
        state: String? = null,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        context: Map<String, String> = emptyMap(),
    ) = UiTraceEvent(
        id = "event-" + sequence,
        timestampEpochMs = time,
        sessionId = "test-session",
        sequence = sequence,
        level = level,
        screen = "RESULT_CARD",
        component = "test",
        event = event,
        state = state,
        context = mapOf("result_generation" to generation) + context,
    )
}
