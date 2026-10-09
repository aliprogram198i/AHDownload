package com.ahdownload.feature.home

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.MediaSourceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardTraceRecorderTest {
    @Test
    fun tracesUnconfirmedBrowserVideoWithoutPersistingSourceUrlOrHeaderValues() {
        val events = mutableListOf<UiTraceEvent>()
        var sequence = 0L
        val logger = UiTraceLogger { screen, component, event, state, context, level ->
            sequence += 1L
            events += UiTraceEvent(
                id = sequence.toString(),
                timestampEpochMs = sequence,
                sessionId = "test-session",
                sequence = sequence,
                level = level,
                screen = screen,
                component = component,
                event = event,
                state = state,
                context = context,
            )
        }
        val source = MediaCandidate(
            id = "browser-video-137",
            sourceUrl = "https://rr1---sn.googlevideo.com/videoplayback?token=private-value",
            format = MediaFormat(
                id = "137",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                height = null,
                hasVideo = true,
                hasAudio = false,
            ),
            requestHeaders = mapOf("Authorization" to "Bearer private-header"),
            sourceContext = MediaSourceContext.BROWSER_OBSERVED,
        )
        val recorder = ResultCardTraceRecorder(logger, "generation-1")

        recorder.recordSnapshot(
            platform = "YouTube",
            kind = MediaKind.Unknown,
            title = "Test video",
            hasThumbnail = true,
            durationMs = 10_000L,
            rawCandidates = listOf(source),
            primaryOptions = emptyList(),
            videoOptions = emptyList(),
            audioOptions = emptyList(),
            directAudioAvailable = false,
            audioAvailable = false,
            visibleVideoOptions = 0,
            videoOptionsExpanded = false,
            selectedCandidateId = null,
            selectedAudioCandidateId = null,
            selectedAudioOutputFormat = null,
        )

        val sourceEvent = events.single { it.event == "SOURCE_CANDIDATE" }
        assertEquals("RESULT_CARD", sourceEvent.screen)
        assertEquals("BLOCKED_AUDIO_TRACK_UNCONFIRMED", sourceEvent.state)
        assertEquals("rr1---sn.googlevideo.com", sourceEvent.context["source_host"])
        assertEquals("1", sourceEvent.context["request_header_count"])
        assertEquals("0", events.single { it.event == "RESULT_SNAPSHOT" }.context["video_picker_option_count"])

        val exportedContext = events.joinToString(" ") { it.context.toString() }
        assertFalse(exportedContext.contains("videoplayback"))
        assertFalse(exportedContext.contains("private-value"))
        assertFalse(exportedContext.contains("private-header"))
        assertTrue(exportedContext.contains("browser-video-137"))
    }

    @Test
    fun recordsSelectionAndSanitizedHttp403State() {
        val events = mutableListOf<UiTraceEvent>()
        var sequence = 0L
        val logger = UiTraceLogger { screen, component, event, state, context, level ->
            sequence += 1L
            events += UiTraceEvent(
                id = sequence.toString(),
                timestampEpochMs = sequence,
                sessionId = "test-session",
                sequence = sequence,
                level = level,
                screen = screen,
                component = component,
                event = event,
                state = state,
                context = context,
            )
        }
        val recorder = ResultCardTraceRecorder(logger, "generation-2")

        recorder.recordAction(
            action = "select_video_quality",
            component = "video_quality_option",
            context = mapOf("candidate_id" to "itag-18", "quality" to "360p"),
        )
        recorder.recordState(
            selectionMode = "VIDEO",
            selectedVideoId = "itag-18",
            selectedAudioId = null,
            selectedAudioOutputFormat = null,
            selectedVideoQuality = "360p",
            selectedAudioQuality = null,
            validatingCandidateId = "itag-18",
            downloadQueued = false,
            favorite = false,
            videoOptionsExpanded = false,
            visibleVideoOptions = 1,
            canDownload = false,
            errorMessage = "HTTP 403 from https://private.example/file?token=private-value",
        )

        assertTrue(events.any { it.event == "USER_ACTION" && it.context["action"] == "select_video_quality" })
        val error = events.single { it.event == "RESULT_CARD_ERROR" }
        assertEquals(DiagnosticLevel.ERROR, error.level)
        assertEquals("HTTP_403", error.context["error_category"])
        assertFalse(error.context["error_summary"].orEmpty().contains("private.example"))
        assertFalse(error.context["error_summary"].orEmpty().contains("private-value"))
    }
}
