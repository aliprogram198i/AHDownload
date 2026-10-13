package com.ahdownload.domain.resolver.social

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookSocialPlatformExtractionStrategyTest {
    @Test
    fun retriesOnMobileHostWhenPrimaryPublicPageReturnsHttp400() = runTest {
        val pageUrl = "https://www.facebook.com/watch/?v=123456789"
        val mobileUrl = "https://m.facebook.com/watch/?v=123456789"
        val mediaUrl = "https://video.xx.fbcdn.net/v/t42/AQExampleVideo.mp4?token=opaque"
        val html = """<html><head><meta property="og:video" content="$mediaUrl"></head><body></body></html>"""
        val fetchedUrls = mutableListOf<String>()
        val headersByUrl = mutableMapOf<String, Map<String, String>>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val pageClient = object : HttpTextClient {
            override suspend fun get(url: String): String = respond(url, emptyMap())

            override suspend fun get(url: String, headers: Map<String, String>): String =
                respond(url, headers)

            private fun respond(url: String, headers: Map<String, String>): String {
                fetchedUrls += url
                headersByUrl[url] = headers
                return when (url) {
                    pageUrl -> throw IOException("HTTP 400")
                    mobileUrl -> html
                    else -> error("Unexpected Facebook fallback URL")
                }
            }
        }
        val resolver = FacebookResolverAdapter(
            provider = FacebookProvider(pageUrl),
            logger = com.ahdownload.core.common.DiagnosticLogger { _, type, _, _, context, _ ->
                events += type to context
            },
            pageClient = pageClient,
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = pageUrl,
                    normalizedUrl = pageUrl,
                    platform = MediaPlatform.Facebook,
                    kind = MediaKind.Video,
                ),
                operationId = "facebook-mobile-fallback-test",
            ),
        )

        assertTrue("Mobile fallback should resolve a public Facebook media URL", result is ResolverResult.Success)
        val candidate = (result as ResolverResult.Success).candidates.single()
        assertEquals(mediaUrl, candidate.sourceUrl)
        assertEquals(MediaKind.Video, candidate.format.kind)
        assertEquals(listOf(pageUrl, mobileUrl), fetchedUrls)
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            headersByUrl[pageUrl]?.get("Accept"))
        assertEquals("https://www.facebook.com/", headersByUrl[pageUrl]?.get("Referer"))
        assertTrue(headersByUrl[pageUrl]?.get("User-Agent").orEmpty().contains("Android"))
        assertEquals("https://www.facebook.com/", headersByUrl[mobileUrl]?.get("Referer"))

        val primaryFailure = events.single { it.first == "SOCIAL_PAGE_FETCH_FAILED" }
        assertEquals("400", primaryFailure.second["http_status"])
        val fallbackEvent = events.single { it.first == "SOCIAL_FACEBOOK_MOBILE_FALLBACK_RESULT" }
        assertEquals("media_found", fallbackEvent.second["fallback_status"])
        assertEquals("1", fallbackEvent.second["media_count"])
        assertEquals("facebook-mobile-fallback-test", fallbackEvent.second["operation_id"])
    }

    @Test
    fun doesNotRewriteNonFacebookPageHostsToFacebookMobileHost() = runTest {
        val pageUrl = "https://example.com/watch/?v=123456789"
        val fetchedUrls = mutableListOf<String>()
        val pageClient = object : HttpTextClient {
            override suspend fun get(url: String): String {
                fetchedUrls += url
                return "<html><head><title>Page without media</title></head><body></body></html>"
            }

            override suspend fun get(url: String, headers: Map<String, String>): String = get(url)
        }
        val resolver = FacebookResolverAdapter(
            provider = FacebookProvider(pageUrl),
            pageClient = pageClient,
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = pageUrl,
                    normalizedUrl = pageUrl,
                    platform = MediaPlatform.Facebook,
                    kind = MediaKind.Video,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
        assertEquals(listOf(pageUrl), fetchedUrls)
    }

    private class FacebookProvider(
        private val pageUrl: String,
    ) : BrowserMediaSessionProvider {
        override suspend fun snapshot(
            url: String,
            platform: MediaPlatform,
        ): BrowserMediaSession = BrowserMediaSession(
            platform = platform,
            pageUrl = pageUrl,
            finalUrl = pageUrl,
            title = "Facebook public video",
        )
    }
}
