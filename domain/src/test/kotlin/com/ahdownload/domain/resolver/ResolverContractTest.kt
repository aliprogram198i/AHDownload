package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverContractTest {
    @Test
    fun requestCarriesLinkAndOptionalKind() {
        val link = MediaLink(
            originalUrl = "https://youtu.be/example",
            normalizedUrl = "https://youtu.be/example",
            platform = MediaPlatform.YouTube,
            kind = MediaKind.Unknown,
        )

        val request = ResolverRequest(link = link, requestedKind = MediaKind.Video)

        assertEquals(MediaPlatform.YouTube, request.link.platform)
        assertEquals(MediaKind.Video, request.requestedKind)
    }

    @Test
    fun rankerPrefersMuxedHigherQualityVideo() {
        val candidates = listOf(
            candidate(
                id = "audio",
                kind = MediaKind.Audio,
                hasVideo = false,
                hasAudio = true,
                bitrateKbps = 320,
            ),
            candidate(
                id = "video-720",
                kind = MediaKind.Video,
                hasVideo = true,
                hasAudio = true,
                height = 720,
                bitrateKbps = 2500,
            ),
            candidate(
                id = "video-1080",
                kind = MediaKind.Video,
                hasVideo = true,
                hasAudio = true,
                height = 1080,
                bitrateKbps = 4500,
            ),
            candidate(
                id = "video-only-1440",
                kind = MediaKind.Video,
                hasVideo = true,
                hasAudio = false,
                height = 1440,
                bitrateKbps = 6000,
            ),
        )

        val ranked = CandidateRanker().rank(candidates, requestedKind = MediaKind.Video)

        assertEquals(listOf("video-only-1440", "video-1080", "video-720"), ranked.map { it.id })
    }

    @Test
    fun adapterCapabilityDeclaresPlatformAndKinds() {
        val adapter = object : PlatformAdapter {
            override val capability = ResolverCapability(
                platform = MediaPlatform.YouTube,
                supportedKinds = setOf(MediaKind.Video, MediaKind.Audio),
            )

            override suspend fun resolve(request: ResolverRequest): ResolverResult {
                return ResolverResult.Failure(FailureCode.NoCandidates)
            }
        }

        assertEquals(MediaPlatform.YouTube, adapter.capability.platform)
        assertEquals(
            setOf(MediaKind.Video, MediaKind.Audio),
            adapter.capability.supportedKinds,
        )
    }

    @Test
    fun resolverResultSuccessCarriesMetadataAndCandidates() {
        val candidate = candidate(
            id = "video-720",
            kind = MediaKind.Video,
            hasVideo = true,
            hasAudio = true,
            height = 720,
        )

        val result = ResolverResult.Success(
            title = "Example",
            thumbnailUrl = null,
            durationMs = 12_000L,
            candidates = listOf(candidate),
        )

        assertEquals("Example", result.title)
        assertEquals(12_000L, result.durationMs)
        assertEquals("video-720", result.candidates.single().id)
    }

    private fun candidate(
        id: String,
        kind: MediaKind,
        hasVideo: Boolean,
        hasAudio: Boolean,
        height: Int? = null,
        bitrateKbps: Int? = null,
    ): MediaCandidate {
        return MediaCandidate(
            id = id,
            sourceUrl = "https://cdn.example.com/$id",
            format = MediaFormat(
                id = id,
                kind = kind,
                container = if (kind == MediaKind.Video) MediaContainer.Mp4 else MediaContainer.M4a,
                height = height,
                bitrateKbps = bitrateKbps,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
            ),
        )
    }
}
