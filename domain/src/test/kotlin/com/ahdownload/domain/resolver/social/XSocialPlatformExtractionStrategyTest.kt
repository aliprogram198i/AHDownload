package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.resolver.HttpTextClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class XSocialPlatformExtractionStrategyTest {
    private val strategy = XSocialPlatformExtractionStrategy()
    private val logger = DiagnosticLogger { _, _, _, _, _, _ -> }

    @Test
    fun extractsMp4AndHlsVariantsFromPublicSyndicationResponse() = runTest {
        val client = FakeHttpTextClient(
            """
            {
              "text": "Public X post with video",
              "mediaDetails": [
                {
                  "type": "video",
                  "media_url_https": "https://pbs.twimg.com/ext_tw_video_thumb/123/pu/img/poster.jpg",
                  "video_info": {
                    "duration_millis": 4200,
                    "variants": [
                      {
                        "bitrate": 832000,
                        "content_type": "video/mp4",
                        "url": "https://video.twimg.com/ext_tw_video/123/pu/vid/720x1280/clip.mp4?tag=12"
                      },
                      {
                        "content_type": "application/x-mpegURL",
                        "url": "https://video.twimg.com/ext_tw_video/123/pu/pl/clip.m3u8?tag=12"
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample/status/1234567890123456789",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-1",
            logger = logger,
        )

        assertNotNull(result)
        assertEquals("Public X post with video", result.title)
        assertEquals(4200L, result.durationMs)
        assertEquals(
            setOf(
                "https://video.twimg.com/ext_tw_video/123/pu/vid/720x1280/clip.mp4?tag=12",
                "https://video.twimg.com/ext_tw_video/123/pu/pl/clip.m3u8?tag=12",
            ),
            result.mediaUrls.toSet(),
        )
        assertEquals(1, client.calls)
        assertTrue(client.requestedUrl.orEmpty().startsWith("https://cdn.syndication.twimg.com/tweet-result?"))
        assertTrue(client.requestedUrl.orEmpty().contains("id=1234567890123456789"))
        assertEquals("application/json", client.requestHeaders["Accept"])
        assertEquals("https://x.com/sample/status/1234567890123456789", client.requestHeaders["Referer"])
    }

    @Test
    fun acceptsTwitterAliasAndIStatusUrl() = runTest {
        val client = FakeHttpTextClient(
            """{"text":"alias tweet","mediaDetails":[{"video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/video/test.mp4"}]}}]}""",
        )

        val result = strategy.extractAdditionalMedia(
            pageUrl = "https://mobile.twitter.com/i/status/987654321",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-2",
            logger = logger,
        )

        assertNotNull(result)
        assertEquals(listOf("https://video.twimg.com/video/test.mp4"), result.mediaUrls)
        assertTrue(client.requestedUrl.orEmpty().contains("id=987654321"))
    }

    @Test
    fun skipsSyndicationWhenAnotherExtractorAlreadyFoundMedia() = runTest {
        val client = FakeHttpTextClient("{}")

        val browserResult = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample/status/123456789",
            browserMediaCount = 1,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-3",
            logger = logger,
        )
        val pageResult = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample/status/123456789",
            browserMediaCount = 0,
            pageFallbackMediaCount = 1,
            pageClient = client,
            operationId = "op-x-4",
            logger = logger,
        )

        assertNull(browserResult)
        assertNull(pageResult)
        assertEquals(0, client.calls)
    }

    @Test
    fun doesNotRequestSyndicationForNonXOrNonStatusUrls() = runTest {
        val client = FakeHttpTextClient("{}")

        val otherHost = strategy.extractAdditionalMedia(
            pageUrl = "https://evil.example/sample/status/123456789",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-5",
            logger = logger,
        )
        val noStatus = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-6",
            logger = logger,
        )
        val malformedId = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample/status/not-a-number",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-7",
            logger = logger,
        )

        assertNull(otherHost)
        assertNull(noStatus)
        assertNull(malformedId)
        assertEquals(0, client.calls)
    }

    @Test
    fun rejectsImageOnlyPayloadsAndUntrustedMediaHosts() = runTest {
        val client = FakeHttpTextClient(
            """
            {
              "text": "Not a playable video",
              "mediaDetails": [
                {
                  "media_url_https": "https://pbs.twimg.com/ext_tw_video_thumb/123/pu/img/poster.jpg",
                  "video_info": {
                    "variants": [
                      {"content_type":"video/mp4","url":"https://evil.example/clip.mp4"},
                      {"content_type":"image/jpeg","url":"https://pbs.twimg.com/ext_tw_video_thumb/123/poster.jpg"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample/status/123456789",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-8",
            logger = logger,
        )

        assertNull(result)
    }

    @Test
    fun handlesMalformedSyndicationJsonWithoutThrowing() = runTest {
        val client = FakeHttpTextClient("""{"legacy":"unexpected-shape","mediaDetails":[]}""")

        val result = strategy.extractAdditionalMedia(
            pageUrl = "https://x.com/sample/status/123456789",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "op-x-9",
            logger = logger,
        )

        assertNull(result)
    }

    @Test
    fun suppliesAnXRefererForFallbackCandidates() {
        val headers = strategy.requestHeadersForCandidate(
            sourceUrl = "https://video.twimg.com/clip.mp4",
            safeHeaders = emptyMap(),
        )
        assertEquals("https://x.com/", headers["Referer"])

        val captured = mapOf("Referer" to "https://x.com/sample/status/123")
        assertEquals(captured, strategy.requestHeadersForCandidate("https://video.twimg.com/clip.mp4", captured))
    }

    private class FakeHttpTextClient(
        private val response: String,
    ) : HttpTextClient {
        var calls: Int = 0
            private set
        var requestedUrl: String? = null
            private set
        var requestHeaders: Map<String, String> = emptyMap()
            private set

        override suspend fun get(url: String): String {
            calls++
            requestedUrl = url
            return response
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            diagnosticContext: Map<String, String>,
        ): String {
            calls++
            requestedUrl = url
            requestHeaders = headers
            return response
        }
    }
}
