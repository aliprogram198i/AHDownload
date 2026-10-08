package com.ahdownload.domain.resolver.social

import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialPlatformResolverTest {
    @Test
    fun observedVideoWithoutAudioTrackIsNotMarkedMuxed() = runTest {
        val url = "https://cdn.example.com/video.mp4"
        val resolver = SocialPlatformResolver(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.TikTok,
                    pageUrl = "https://www.tiktok.com/@user/video/123456",
                    mediaUrls = listOf(url),
                    mediaHasAudioByUrl = mapOf(url to false),
                ),
            ),
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.TikTok),
            ),
        )

        val candidate = (result as ResolverResult.Success).candidates.single()
        assertFalse(candidate.format.hasAudio)
    }

    @Test
    fun hangingBrowserSessionReturnsExplicitTimeoutFailure() = runTest {
        val resolver = SocialPlatformResolver(
            provider = object : BrowserMediaSessionProvider {
                override suspend fun snapshot(
                    url: String,
                    platform: MediaPlatform,
                ): BrowserMediaSession {
                    delay(1_000L)
                    error("unreachable")
                }
            },
            resolveTimeoutMs = 25L,
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.Instagram),
            ),
        )

        assertTrue(result is ResolverResult.Failure)
        assertTrue(
            (result as ResolverResult.Failure).code ==
                com.ahdownload.domain.resolver.FailureCode.ResolverTimeout,
        )
    }

    @Test
    fun hangingPageFetchReturnsExplicitTimeoutFailure() = runTest {
        val resolver = SocialPlatformResolver(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "https://www.instagram.com/reel/ABC123/",
                    mediaUrls = emptyList(),
                ),
            ),
            pageClient = object : HttpTextClient {
                override suspend fun get(url: String): String {
                    delay(1_000L)
                    return ""
                }

                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                ): String = get(url)

                override suspend fun postJson(url: String, body: String): String = ""

                override suspend fun postJson(
                    url: String,
                    body: String,
                    headers: Map<String, String>,
                ): String = ""
            },
            resolveTimeoutMs = 25L,
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.Instagram),
            ),
        )

        assertTrue(result is ResolverResult.Failure)
        assertTrue(
            (result as ResolverResult.Failure).code ==
                com.ahdownload.domain.resolver.FailureCode.ResolverTimeout,
        )
    }

    @Test
    fun customSchemeFinalUrlFallsBackToValidHttpSessionPageUrl() = runTest {
        val pageUrl = "https://www.instagram.com/reel/ABC123/"
        val mediaUrl = "https://cdn.example.com/video.mp4"
        val fetchedUrls = mutableListOf<String>()
        val resolver = SocialPlatformResolver(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = pageUrl,
                    finalUrl = "instagram://reel/ABC123",
                    mediaUrls = listOf(mediaUrl),
                ),
            ),
            pageClient = RecordingTextClient { fetchedUrls += it },
        )

        val result = resolver.resolve(
            ResolverRequest(link = link(MediaPlatform.Instagram)),
        )

        assertTrue(result is ResolverResult.Success)
        assertEquals(listOf(pageUrl), fetchedUrls)
    }

    @Test
    fun customSchemeUrlsNeverReachHttpPageClient() = runTest {
        val mediaUrl = "https://cdn.example.com/video.mp4"
        val fetchedUrls = mutableListOf<String>()
        val resolver = SocialPlatformResolver(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "instagram://reel/ABC123",
                    finalUrl = "instagram://reel/ABC123",
                    mediaUrls = listOf(mediaUrl),
                ),
            ),
            pageClient = RecordingTextClient { fetchedUrls += it },
        )
        val deepLink = link(MediaPlatform.Instagram).copy(
            originalUrl = "instagram://reel/ABC123",
            normalizedUrl = "instagram://reel/ABC123",
        )

        val result = resolver.resolve(ResolverRequest(link = deepLink))

        assertTrue(result is ResolverResult.Success)
        assertTrue(fetchedUrls.isEmpty())
    }

    @Test
    fun observedVideoWithAudioTrackRemainsMuxed() = runTest {
        val url = "https://cdn.example.com/video.mp4"
        val resolver = SocialPlatformResolver(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "https://www.instagram.com/reel/ABC123/",
                    mediaUrls = listOf(url),
                    mediaHasAudioByUrl = mapOf(url to true),
                ),
            ),
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.Instagram),
            ),
        )

        val candidate = (result as ResolverResult.Success).candidates.single()
        assertTrue(candidate.format.hasAudio)
    }

    private fun link(platform: MediaPlatform) =
        com.ahdownload.domain.model.MediaLink(
            originalUrl = "https://example.com/post",
            normalizedUrl = "https://example.com/post",
            platform = platform,
            kind = com.ahdownload.domain.model.MediaKind.Video,
        )

    private class RecordingTextClient(
        private val onGet: (String) -> Unit,
    ) : HttpTextClient {
        override suspend fun get(url: String): String {
            onGet(url)
            return ""
        }

        override suspend fun get(url: String, headers: Map<String, String>): String = get(url)

        override suspend fun postJson(url: String, body: String): String = ""

        override suspend fun postJson(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): String = ""
    }

    private class FakeProvider(
        private val session: BrowserMediaSession,
    ) : BrowserMediaSessionProvider {
        override suspend fun snapshot(
            url: String,
            platform: MediaPlatform,
        ): BrowserMediaSession = session
    }
}
