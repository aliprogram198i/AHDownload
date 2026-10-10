package com.ahdownload.feature.home

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.MediaSourceContext
import com.ahdownload.domain.resolver.SmartResultEngine
import com.ahdownload.domain.validation.CandidateValidationResult
import com.ahdownload.domain.validation.ValidationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPickerOptionClassifierTest {
    @Test
    fun exposesUnconfirmedBrowserVideoOnlyAsExplicitFallback() {
        val source = MediaCandidate(
            id = "browser-video-0",
            sourceUrl = "https://cdn.example/video",
            format = MediaFormat(
                id = "webview-video-0",
                kind = MediaKind.Video,
                container = MediaContainer.Unknown,
                hasVideo = true,
                hasAudio = false,
            ),
            sourceContext = MediaSourceContext.BROWSER_OBSERVED,
        )
        val primaryOptions = SmartResultEngine().build(listOf(source)).all

        val result = classifyVideoPickerOptions(primaryOptions, directAudioAvailable = false)

        assertTrue(result.mainOptions.isEmpty())
        assertEquals(listOf("browser-video-0"), result.videoOnlyFallback.map { it.candidate.id })
        assertEquals(listOf("browser-video-0"), result.selectableOptions.map { it.candidate.id })
    }

    @Test
    fun videoOnlySourceCanJoinMainPickerWhenCompanionAudioIsAvailable() {
        val source = MediaCandidate(
            id = "browser-video-0",
            sourceUrl = "https://cdn.example/video",
            format = MediaFormat(
                id = "webview-video-0",
                kind = MediaKind.Video,
                container = MediaContainer.Unknown,
                hasVideo = true,
                hasAudio = false,
            ),
            sourceContext = MediaSourceContext.BROWSER_OBSERVED,
        )
        val primaryOptions = SmartResultEngine().build(listOf(source)).all

        val result = classifyVideoPickerOptions(primaryOptions, directAudioAvailable = true)

        assertEquals(listOf("browser-video-0"), result.mainOptions.map { it.candidate.id })
        assertTrue(result.videoOnlyFallback.isEmpty())
    }

    @Test
    fun doesNotPromoteAudioOnlyCandidatesIntoVideoPicker() {
        val audio = MediaCandidate(
            id = "audio-only",
            sourceUrl = "https://cdn.example/audio",
            format = MediaFormat(
                id = "audio",
                kind = MediaKind.Audio,
                container = MediaContainer.M4a,
                hasVideo = false,
                hasAudio = true,
            ),
        )
        val result = classifyVideoPickerOptions(
            SmartResultEngine().build(listOf(audio)).all,
            directAudioAvailable = true,
        )

        assertTrue(result.mainOptions.isEmpty())
        assertTrue(result.videoOnlyFallback.isEmpty())
    }

    @Test
    fun retriesAlternateAudioSourceWhenPreferredSourceFailsValidation() = kotlinx.coroutines.runBlocking {
        val aac = MediaCandidate(
            id = "audio-aac",
            sourceUrl = "https://cdn.example/audio-aac",
            format = MediaFormat(
                id = "140",
                kind = MediaKind.Audio,
                container = MediaContainer.Mp4,
                audioCodec = "mp4a.40.2",
                bitrateKbps = 130,
                hasVideo = false,
                hasAudio = true,
            ),
        )
        val opus = MediaCandidate(
            id = "audio-opus",
            sourceUrl = "https://cdn.example/audio-opus",
            format = MediaFormat(
                id = "251",
                kind = MediaKind.Audio,
                container = MediaContainer.Webm,
                audioCodec = "opus",
                bitrateKbps = 119,
                hasVideo = false,
                hasAudio = true,
            ),
        )
        val attempted = mutableListOf<String>()

        val result = validateCompanionAudioCandidates(listOf(opus, aac)) { candidate ->
            attempted += candidate.id
            if (candidate.id == "audio-aac") {
                CandidateValidationResult.Invalid(ValidationFailure.HttpStatus(403))
            } else {
                CandidateValidationResult.Valid(candidate, candidate.sourceUrl)
            }
        }

        assertEquals(listOf("audio-aac", "audio-opus"), attempted)
        assertEquals("audio-opus", result?.candidate?.id)
        assertEquals("https://cdn.example/audio-opus", result?.validation?.finalUrl)
    }

    @Test
    fun rejectsVideoSourcesAsCompanionAudioAndDoesNotRetryDuplicateEntries() = kotlinx.coroutines.runBlocking {
        val audio = MediaCandidate(
            id = "audio",
            sourceUrl = "https://cdn.example/audio",
            format = MediaFormat(
                id = "140",
                kind = MediaKind.Audio,
                container = MediaContainer.Mp4,
                audioCodec = "mp4a.40.2",
                bitrateKbps = 130,
                hasVideo = false,
                hasAudio = true,
            ),
        )
        val video = MediaCandidate(
            id = "video",
            sourceUrl = "https://cdn.example/video",
            format = MediaFormat(
                id = "136",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                hasVideo = true,
                hasAudio = false,
            ),
        )
        var attempts = 0

        val result = validateCompanionAudioCandidates(listOf(audio, audio, video)) {
            attempts++
            CandidateValidationResult.Invalid(ValidationFailure.HttpStatus(403))
        }

        assertEquals(1, attempts)
        assertEquals(null, result)
    }

}
