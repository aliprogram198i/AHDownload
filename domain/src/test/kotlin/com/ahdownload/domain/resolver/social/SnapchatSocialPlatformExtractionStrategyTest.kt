package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapchatSocialPlatformExtractionStrategyTest {
    @Test
    fun resolvesRequestedSpotlightContentUrlWithoutMistakingThumbnailForVideo() = runTest {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val resolver = resolver(
            html = spotlightHtml(contentUrl = VIDEO_URL).replace(
                "<html><body>",
                """<html><head><meta property="og:video" content="https://cdn.example.com/unrelated-recommended-video.mp4"></head><body>""",
            ),
            logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context },
        )

        val result = resolver.resolve(
            ResolverRequest(link = videoLink(), operationId = "snapchat-spotlight-test"),
        )

        assertTrue("Expected successful result, got $result", result is ResolverResult.Success)
        val success = result as ResolverResult.Success
        assertEquals("Spotlight test clip", success.title)
        assertEquals(THUMBNAIL_URL, success.thumbnailUrl)
        assertEquals(12_400L, success.durationMs)
        assertEquals(1, success.candidates.size)

        val candidate = success.candidates.single()
        assertEquals(VIDEO_URL, candidate.sourceUrl)
        assertEquals(MediaKind.Video, candidate.format.kind)
        assertEquals(com.ahdownload.domain.resolver.MediaContainer.Mp4, candidate.format.container)
        assertTrue(candidate.format.hasVideo)
        assertFalse(candidate.sourceUrl.contains("thumbnail"))
        assertEquals("https://www.snapchat.com/", candidate.requestHeaders["Referer"])

        val fallback = events.single { it.first == "SOCIAL_SNAPCHAT_SPOTLIGHT_FALLBACK_RESULT" }
        assertEquals("media_found", fallback.second["fallback_status"])
        assertEquals("1", fallback.second["media_count"])
        assertEquals("snapchat-spotlight-test", fallback.second["operation_id"])
    }

    @Test
    fun rejectsContentUrlOutsideSnapchatMediaCdn() = runTest {
        val resolver = resolver(
            html = spotlightHtml(contentUrl = "https://media.attacker.example/opaque-video-id"),
        )

        val result = resolver.resolve(ResolverRequest(link = videoLink()))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
    }

    @Test
    fun rejectsSpotlightMetadataBelongingToAnotherVideo() = runTest {
        val resolver = resolver(
            html = spotlightHtml(
                storyId = "W7_EDlXWTBiXAEEniNoMPwAAYYWtidGhudGZpAX1TKn0JAX1TKnXJBBBBBB",
                contentUrl = VIDEO_URL,
            ),
        )

        val result = resolver.resolve(ResolverRequest(link = videoLink()))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
    }

    @Test
    fun rejectsImageUrlEvenWhenItAppearsInVideoMetadata() = runTest {
        val resolver = resolver(
            html = spotlightHtml(contentUrl = "https://cf-st.sc-cdn.net/i/thumbnail.jpg?token=1"),
        )

        val result = resolver.resolve(ResolverRequest(link = videoLink()))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
    }

    @Test
    fun malformedNextDataReturnsNoCandidatesWithoutThrowing() = runTest {
        val resolver = resolver(
            html = """<html><script id="__NEXT_DATA__" type="application/json">not-json</script></html>""",
        )

        val result = resolver.resolve(ResolverRequest(link = videoLink()))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
    }

    private fun resolver(
        html: String,
        logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    ) = SnapchatResolverAdapter(
        provider = FakeProvider(
            BrowserMediaSession(
                platform = MediaPlatform.Snapchat,
                pageUrl = SPOTLIGHT_URL,
                mediaUrls = emptyList(),
            ),
        ),
        logger = logger,
        pageClient = StaticHtmlClient(html),
    )

    private fun videoLink() = MediaLink(
        originalUrl = SPOTLIGHT_URL,
        normalizedUrl = SPOTLIGHT_URL,
        platform = MediaPlatform.Snapchat,
        kind = MediaKind.Video,
    )

    private fun spotlightHtml(
        storyId: String = SPOTLIGHT_ID,
        contentUrl: String,
    ): String = """
        <html><body>
        <script id="__NEXT_DATA__" type="application/json">
        {
          "props": {
            "pageProps": {
              "spotlightFeed": {
                "spotlightStories": [{
                  "story": { "storyId": { "value": "$storyId" } },
                  "metadata": [{
                    "videoMetadata": {
                      "name": "Spotlight test clip",
                      "contentUrl": "$contentUrl",
                      "thumbnailUrl": "$THUMBNAIL_URL",
                      "durationMs": 12400
                    }
                  }]
                }]
              }
            }
          }
        }
        </script>
        </body></html>
    """.trimIndent()

    private class StaticHtmlClient(
        private val html: String,
    ) : HttpTextClient {
        override suspend fun get(url: String): String = html
        override suspend fun get(url: String, headers: Map<String, String>): String = html
        override suspend fun get(url: String, headers: Map<String, String>, diagnosticContext: Map<String, String>): String = html
        override suspend fun postJson(url: String, body: String): String = ""
        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String = ""
        override suspend fun postJson(url: String, body: String, headers: Map<String, String>, diagnosticContext: Map<String, String>): String = ""
    }

    private class FakeProvider(
        private val session: BrowserMediaSession,
    ) : BrowserMediaSessionProvider {
        override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession = session
    }

    private companion object {
        const val SPOTLIGHT_ID = "W7_EDlXWTBiXAEEniNoMPwAAYYmZiZ2J2a3VhAZgD5F41AZgD5FXFAAAAAQ"
        const val SPOTLIGHT_URL = "https://www.snapchat.com/@anjulaeditz/spotlight/$SPOTLIGHT_ID"
        const val VIDEO_URL = "https://cf-st.sc-cdn.net/d/spotlight-video-token?token=signed"
        const val THUMBNAIL_URL = "https://cf-st.sc-cdn.net/i/spotlight-thumbnail.256.IRZXSOY"
    }
}
