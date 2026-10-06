package com.ahdownload.domain.validation

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CandidateValidatorTest {

    @Test
    fun validatesVideoAndBackfillsSize() = kotlinx.coroutines.test.runTest {
        val candidate = candidate(MediaKind.Video)
        val validator = CandidateValidator(
            FakeProbe(
                MediaProbeResult(
                    statusCode = 206,
                    contentType = "video/mp4",
                    contentLengthBytes = 1234L,
                    finalUrl = "https://cdn.example/video.mp4",
                ),
            ),
        )

        val result = validator.validate(candidate)

        val valid = assertIs<CandidateValidationResult.Valid>(result)
        assertEquals(1234L, valid.candidate.format.fileSizeBytes)
        assertEquals("https://cdn.example/video.mp4", valid.finalUrl)
    }

    @Test
    fun rejectsHtmlResponse() = kotlinx.coroutines.test.runTest {
        val result = CandidateValidator(
            FakeProbe(
                MediaProbeResult(
                    statusCode = 200,
                    contentType = "text/html; charset=utf-8",
                    contentLengthBytes = 10L,
                    finalUrl = "https://example.com/page",
                ),
            ),
        ).validate(candidate(MediaKind.Video))

        assertEquals(
            ValidationFailure.HtmlResponse,
            assertIs<CandidateValidationResult.Invalid>(result).failure,
        )
    }

    @Test
    fun rejectsContentTypeMismatch() = kotlinx.coroutines.test.runTest {
        val result = CandidateValidator(
            FakeProbe(
                MediaProbeResult(
                    statusCode = 200,
                    contentType = "audio/mp4",
                    contentLengthBytes = 10L,
                    finalUrl = "https://cdn.example/audio.mp4",
                ),
            ),
        ).validate(candidate(MediaKind.Video))

        assertEquals(
            ValidationFailure.ContentTypeMismatch,
            assertIs<CandidateValidationResult.Invalid>(result).failure,
        )
    }

    @Test
    fun rejectsNonSuccessHttpStatus() = kotlinx.coroutines.test.runTest {
        val result = CandidateValidator(
            FakeProbe(
                MediaProbeResult(
                    statusCode = 403,
                    contentType = "video/mp4",
                    contentLengthBytes = null,
                    finalUrl = "https://cdn.example/video.mp4",
                ),
            ),
        ).validate(candidate(MediaKind.Video))

        assertEquals(
            ValidationFailure.HttpStatus(403),
            assertIs<CandidateValidationResult.Invalid>(result).failure,
        )
    }

    @Test
    fun rejectsUnsupportedSchemeBeforeProbe() = kotlinx.coroutines.test.runTest {
        var called = false
        val probe = object : MediaProbe {
            override suspend fun probe(url: String, headers: Map<String, String>): MediaProbeResult {
                called = true
                error("probe must not be called")
            }
        }

        val result = CandidateValidator(probe).validate(
            candidate(MediaKind.Video).copy(sourceUrl = "file:///tmp/video.mp4"),
        )

        assertEquals(ValidationFailure.InvalidUrl, assertIs<CandidateValidationResult.Invalid>(result).failure)
        assertEquals(false, called)
    }

    private fun candidate(kind: MediaKind) = MediaCandidate(
        id = "itag-18",
        sourceUrl = "https://cdn.example/media",
        format = MediaFormat(
            id = "itag-18",
            kind = kind,
            container = if (kind == MediaKind.Video) MediaContainer.Mp4 else MediaContainer.M4a,
            width = if (kind == MediaKind.Video) 1280 else null,
            height = if (kind == MediaKind.Video) 720 else null,
            hasVideo = kind == MediaKind.Video,
            hasAudio = kind == MediaKind.Video || kind == MediaKind.Audio,
        ),
    )

    private class FakeProbe(
        private val result: MediaProbeResult,
    ) : MediaProbe {
        override suspend fun probe(url: String, headers: Map<String, String>): MediaProbeResult = result
    }
}
