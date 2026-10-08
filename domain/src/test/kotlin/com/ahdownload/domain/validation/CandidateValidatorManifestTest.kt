package com.ahdownload.domain.validation

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.MediaSourceContext
import kotlin.test.Test
import kotlin.test.assertIs

class CandidateValidatorManifestTest {

    @Test
    fun acceptsHlsManifestMimeForStreamingCandidate() = kotlinx.coroutines.test.runTest {
        val validator = CandidateValidator(
            probe = StaticProbe("application/vnd.apple.mpegurl"),
        )

        val candidate = candidate("https://cdn.example.com/master.m3u8")

        assertIs<CandidateValidationResult.Valid>(validator.validate(candidate))
    }

    @Test
    fun acceptsDashManifestMimeForStreamingCandidate() = kotlinx.coroutines.test.runTest {
        val validator = CandidateValidator(
            probe = StaticProbe("application/dash+xml"),
        )

        val candidate = candidate("https://cdn.example.com/stream.mpd")

        assertIs<CandidateValidationResult.Valid>(validator.validate(candidate))
    }

    private fun candidate(url: String) = MediaCandidate(
        id = "manifest",
        sourceUrl = url,
        sourceContext = MediaSourceContext.RESOLVER_GENERATED,
        streamingManifest = true,
        format = MediaFormat(
            id = "manifest",
            kind = MediaKind.Video,
            container = MediaContainer.Mkv,
            hasVideo = true,
            hasAudio = false,
        ),
    )

    private class StaticProbe(
        private val contentType: String,
    ) : MediaProbe {
        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            operationId: String?,
            sourceContext: MediaSourceContext,
        ): MediaProbeResult = MediaProbeResult(
            statusCode = 200,
            contentType = contentType,
            contentLengthBytes = null,
            finalUrl = url,
            method = "HEAD",
        )
    }
}
