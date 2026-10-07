package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateRankerTest {

    @Test
    fun prioritizesBrowserObservedCandidateDuringRecovery() {
        val resolver = MediaCandidate(
            id = "resolver",
            sourceUrl = "https://rr.googlevideo.com/videoplayback?itag=18",
            format = MediaFormat(
                id = "18",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                height = 1080,
                bitrateKbps = 5000,
                hasVideo = true,
                hasAudio = true,
            ),
            sourceContext = MediaSourceContext.RESOLVER_GENERATED,
        )
        val browser = MediaCandidate(
            id = "browser",
            sourceUrl = "https://rr.googlevideo.com/videoplayback?itag=22&pot=browser",
            format = MediaFormat(
                id = "22",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                height = 720,
                bitrateKbps = 2500,
                hasVideo = true,
                hasAudio = true,
            ),
            sourceContext = MediaSourceContext.BROWSER_OBSERVED,
        )

        val ranked = CandidateRanker().rank(
            listOf(resolver, browser),
            requestedKind = MediaKind.Video,
        )

        assertEquals(listOf("browser", "resolver"), ranked.map { it.id })
    }
}
