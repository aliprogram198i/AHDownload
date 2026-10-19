package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.resolver.HttpTextClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokSocialPlatformExtractionStrategyTest {
    @Test
    fun publicEmbedFallbackExtractsOnlyTikTokCdnVideoUrls() = runTest {
        val pageUrl = "https://www.tiktok.com/@creator/video/7345678901234567890"
        val mediaUrl = "https://v16-webapp.tiktokcdn.com/video/tos/sample.mp4?mime_type=video_mp4"
        val externalUrl = "https://example.org/not-tiktok.mp4"
        val requests = mutableListOf<String>()
        var headers = emptyMap<String, String>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = error("Headers are required for TikTok fallback")
            override suspend fun get(url: String, requestHeaders: Map<String, String>): String {
                requests += url
                headers = requestHeaders
                return """<html><head><meta property="og:title" content="Public clip"></head><script>{"playAddr":"https:\/\/v16-webapp.tiktokcdn.com\/video\/tos\/sample.mp4?mime_type=video_mp4","downloadAddr":"$externalUrl"}</script></html>"""
            }
            override suspend fun get(
                url: String,
                requestHeaders: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String = get(url, requestHeaders)
        }
        val strategy = TikTokSocialPlatformExtractionStrategy()

        val result = strategy.extractAdditionalMedia(
            pageUrl = pageUrl,
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "tiktok-public-embed-test",
            logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context },
        )

        assertEquals(listOf("https://www.tiktok.com/embed/v2/7345678901234567890"), requests)
        assertTrue(headers["User-Agent"].orEmpty().contains("Android"))
        assertEquals(pageUrl, headers["Referer"])
        assertEquals(listOf(mediaUrl), result?.mediaUrls)
        assertEquals("media_found", events.single().second["fallback_status"])
        assertEquals("tiktok-public-embed-test", events.single().second["operation_id"])
    }

    @Test
    fun fallbackIsSkippedWhenBrowserOrPageAlreadyFoundMedia() = runTest {
        val strategy = TikTokSocialPlatformExtractionStrategy()
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = error("Fallback must not run")
        }

        assertNull(
            strategy.extractAdditionalMedia(
                pageUrl = "https://www.tiktok.com/@creator/video/7345678901234567890",
                browserMediaCount = 1,
                pageFallbackMediaCount = 0,
                pageClient = client,
                operationId = null,
                logger = DiagnosticLogger { _, _, _, _, _, _ -> },
            ),
        )
        assertNull(
            strategy.extractAdditionalMedia(
                pageUrl = "https://www.tiktok.com/@creator/video/7345678901234567890",
                browserMediaCount = 0,
                pageFallbackMediaCount = 1,
                pageClient = client,
                operationId = null,
                logger = DiagnosticLogger { _, _, _, _, _, _ -> },
            ),
        )
    }

    @Test
    fun fallbackRejectsNonTikTokAndNonCanonicalUrls() = runTest {
        val strategy = TikTokSocialPlatformExtractionStrategy()
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = error("Invalid URL must not trigger network")
        }

        assertNull(
            strategy.extractAdditionalMedia(
                pageUrl = "https://example.org/@creator/video/7345678901234567890",
                browserMediaCount = 0,
                pageFallbackMediaCount = 0,
                pageClient = client,
                operationId = null,
                logger = DiagnosticLogger { _, _, _, _, _, _ -> },
            ),
        )
        assertNull(
            strategy.extractAdditionalMedia(
                pageUrl = "https://www.tiktok.com/@creator/video/not-a-video-id",
                browserMediaCount = 0,
                pageFallbackMediaCount = 0,
                pageClient = client,
                operationId = null,
                logger = DiagnosticLogger { _, _, _, _, _, _ -> },
            ),
        )
    }

    @Test
    fun nonCanonicalTikTokPageReportsWhyEmbedFallbackWasSkipped() = runTest {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val strategy = TikTokSocialPlatformExtractionStrategy()
        val result = strategy.extractAdditionalMedia(
            pageUrl = "https://vm.tiktok.com/ZMshortCode/",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = object : HttpTextClient {
                override suspend fun get(url: String): String = error("No canonical video ID is available")
                override suspend fun get(url: String, headers: Map<String, String>): String = get(url)
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    diagnosticContext: Map<String, String>,
                ): String = get(url)
            },
            operationId = "tiktok-short-link-skip-test",
            logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context },
        )

        assertNull(result)
        val event = events.single()
        assertEquals("SOCIAL_TIKTOK_EMBED_FALLBACK_SKIPPED", event.first)
        assertEquals("video_id_not_found", event.second["skip_reason"])
        assertEquals("tiktok", event.second["page_url_host_category"])
        assertEquals("false", event.second["video_id_present"])
        assertEquals("tiktok-short-link-skip-test", event.second["operation_id"])
    }

    @Test
    fun pageHeadersArePlatformScoped() {
        val strategy = TikTokSocialPlatformExtractionStrategy()
        assertTrue(strategy.pageRequestHeaders("https://www.tiktok.com/@creator/video/7345678901234567890").containsKey("User-Agent"))
        assertTrue(strategy.pageRequestHeaders("https://example.org/video.mp4").isEmpty())
    }

    @Test
    fun publicEmbedHttp451IsRecordedAsAStatusWithoutLeakingExceptionText() = runTest {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = throw java.io.IOException("HTTP 451")
            override suspend fun get(url: String, headers: Map<String, String>): String =
                throw java.io.IOException("HTTP 451")
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String = throw java.io.IOException("HTTP 451")
        }

        val result = TikTokSocialPlatformExtractionStrategy().extractAdditionalMedia(
            pageUrl = "https://www.tiktok.com/@creator/video/7345678901234567890",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "tiktok-451-test",
            logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context },
        )

        assertNull(result)
        val event = events.single()
        assertEquals("SOCIAL_TIKTOK_EMBED_FALLBACK_RESULT", event.first)
        assertEquals("http_451_unavailable", event.second["fallback_status"])
        assertEquals("451", event.second["http_status"])
        assertEquals("IOException", event.second["exception_type"])
        assertFalse(event.second.containsKey("exception_message"))
    }
}
