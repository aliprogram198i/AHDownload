package com.ahdownload.domain.resolver.social

import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.PlatformAdapter
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
    fun instagramApiDiscoveredMediaGetsSafeRefererFallback() = runTest {
        val mediaUrl = "https://scontent.cdninstagram.com/o1/v/t2/f2/m367/AQExample.mp4?token=1"
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "https://www.instagram.com/reel/ABC123/",
                    mediaUrls = listOf(mediaUrl),
                    instagramApiStatus = "success_media",
                ),
            ),
        )

        val result = resolver.resolve(ResolverRequest(link = link(MediaPlatform.Instagram)))

        assertTrue(result is ResolverResult.Success)
        assertEquals(
            "https://www.instagram.com/",
            (result as ResolverResult.Success).candidates.single().requestHeaders["Referer"],
        )
    }

    @Test
    fun instagramPublicEmbedFallbackResolvesVideoWhenPageHtmlHasNoMedia() = runTest {
        val pageUrl = "https://www.instagram.com/p/ABC123/"
        val embedUrl = "https://www.instagram.com/p/ABC123/embed/captioned/"
        val mediaUrl = "https://scontent.cdninstagram.com/o1/v/t2/f2/m367/clip.mp4?token=opaque"
        val embedHtml = """<html><head><meta property="og:video" content="$mediaUrl"></head><body>""" +
            "x".repeat(620_000) + "</body></html>"
        val fetchedUrls = mutableListOf<String>()
        val requestHeaders = mutableMapOf<String, Map<String, String>>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = pageUrl,
                    mediaUrls = emptyList(),
                    instagramApiStatus = "legacy_network_error_graphql_error",
                ),
            ),
            logger = com.ahdownload.core.common.DiagnosticLogger { _, type, _, _, context, _ ->
                events += type to context
            },
            pageClient = object : HttpTextClient {
                override suspend fun get(url: String): String {
                    fetchedUrls += url
                    return if (url == embedUrl) {
                        embedHtml
                    } else {
                        """<html><head><title>Instagram</title></head><body></body></html>"""
                    }
                }

                override suspend fun get(url: String, headers: Map<String, String>): String {
                    requestHeaders[url] = headers
                    return get(url)
                }
            },
        )

        val requestLink = link(MediaPlatform.Instagram).copy(
            originalUrl = pageUrl,
            normalizedUrl = pageUrl,
        )
        val result = resolver.resolve(ResolverRequest(link = requestLink, operationId = "instagram-embed-fallback-test"))

        assertTrue(result is ResolverResult.Success)
        assertEquals(mediaUrl, (result as ResolverResult.Success).candidates.single().sourceUrl)
        assertEquals(listOf(pageUrl, embedUrl), fetchedUrls)
        assertEquals(pageUrl, requestHeaders[embedUrl]?.get("Referer"))
        assertTrue(requestHeaders[embedUrl]?.get("User-Agent").orEmpty().contains("Android"))
        val embedEvent = events.single { it.first == "SOCIAL_INSTAGRAM_EMBED_FALLBACK_RESULT" }
        assertEquals("media_found", embedEvent.second["fallback_status"])
        assertEquals(embedHtml.length.toString(), embedEvent.second["response_chars"])
        assertEquals("1", embedEvent.second["media_count"])
        assertEquals("instagram-embed-fallback-test", embedEvent.second["operation_id"])
    }

    @Test
    fun instagramPlainEmbedFallbackIsTriedWhenCaptionedEmbedHasNoMedia() = runTest {
        val pageUrl = "https://www.instagram.com/reel/ABC123/"
        val captionedUrl = "https://www.instagram.com/reel/ABC123/embed/captioned/"
        val plainUrl = "https://www.instagram.com/reel/ABC123/embed/"
        val mediaUrl = "https://scontent.cdninstagram.com/o1/v/t2/f2/plain-clip.mp4?token=opaque"
        val fetchedUrls = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = pageUrl,
                    mediaUrls = emptyList(),
                    instagramApiStatus = "legacy_network_error_graphql_error",
                ),
            ),
            logger = com.ahdownload.core.common.DiagnosticLogger { _, type, _, _, context, _ ->
                events += type to context
            },
            pageClient = object : HttpTextClient {
                override suspend fun get(url: String): String {
                    fetchedUrls += url
                    return when (url) {
                        pageUrl, captionedUrl -> """<html><head><title>Instagram</title></head><body></body></html>"""
                        plainUrl -> """<html><head><meta property="og:video" content="$mediaUrl"></head></html>"""
                        else -> error("Unexpected URL in public embed fallback test")
                    }
                }

                override suspend fun get(url: String, headers: Map<String, String>): String = get(url)
            },
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.Instagram).copy(originalUrl = pageUrl, normalizedUrl = pageUrl),
                operationId = "instagram-plain-embed-test",
            ),
        )

        assertTrue(result is ResolverResult.Success)
        assertEquals(mediaUrl, (result as ResolverResult.Success).candidates.single().sourceUrl)
        assertEquals(listOf(pageUrl, captionedUrl, plainUrl), fetchedUrls)
        val embedEvents = events.filter { it.first == "SOCIAL_INSTAGRAM_EMBED_FALLBACK_RESULT" }
        assertEquals(2, embedEvents.size)
        assertEquals("captioned", embedEvents[0].second["embed_variant"])
        assertEquals("no_media", embedEvents[0].second["fallback_status"])
        assertEquals("plain", embedEvents[1].second["embed_variant"])
        assertEquals("media_found", embedEvents[1].second["fallback_status"])
    }

    @Test
    fun observedVideoWithoutAudioTrackIsNotMarkedMuxed() = runTest {
        val url = "https://cdn.example.com/video.mp4"
        val resolver = TikTokResolverAdapter(
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
        assertEquals(
            com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED,
            candidate.sourceContext,
        )
    }

    @Test
    fun hangingBrowserSessionReturnsExplicitTimeoutFailure() = runTest {
        val resolver = InstagramResolverAdapter(
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
        val resolver = InstagramResolverAdapter(
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
        val resolver = InstagramResolverAdapter(
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

        val requestLink = link(MediaPlatform.Instagram).copy(
            originalUrl = pageUrl,
            normalizedUrl = pageUrl,
        )
        val result = resolver.resolve(
            ResolverRequest(link = requestLink),
        )

        assertTrue(result is ResolverResult.Success)
        assertEquals(listOf(pageUrl), fetchedUrls)
    }

    @Test
    fun customSchemeUrlsNeverReachHttpPageClient() = runTest {
        val mediaUrl = "https://cdn.example.com/video.mp4"
        val fetchedUrls = mutableListOf<String>()
        val resolver = InstagramResolverAdapter(
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
    fun loginRedirectDoesNotReplaceOriginalPostForPageFallback() = runTest {
        val pageUrl = "https://www.instagram.com/reel/ABC123/"
        val redirectedLoginUrl = "https://www.instagram.com/accounts/login/"
        val mediaUrl = "https://cdn.example.com/video.mp4"
        val fetchedUrls = mutableListOf<String>()
        val html = """<html><head><meta property="og:video" content="$mediaUrl"></head></html>"""
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = pageUrl,
                    finalUrl = redirectedLoginUrl,
                    mediaUrls = emptyList(),
                ),
            ),
            pageClient = object : HttpTextClient {
                override suspend fun get(url: String): String {
                    fetchedUrls += url
                    return html
                }

                override suspend fun get(url: String, headers: Map<String, String>): String = get(url)
                override suspend fun postJson(url: String, body: String): String = ""
                override suspend fun postJson(
                    url: String,
                    body: String,
                    headers: Map<String, String>,
                ): String = ""
            },
        )
        val requestLink = link(MediaPlatform.Instagram).copy(
            originalUrl = pageUrl,
            normalizedUrl = pageUrl,
        )

        val result = resolver.resolve(ResolverRequest(link = requestLink))

        assertEquals(listOf(pageUrl), fetchedUrls)
        assertTrue(result is ResolverResult.Success)
        assertEquals(mediaUrl, (result as ResolverResult.Success).candidates.single().sourceUrl)
    }

    @Test
    fun resolverDiagnosticStagesCarryTheSameOperationId() = runTest {
        val operationId = "instagram-op-test"
        val loggedContexts = mutableListOf<Pair<String, Map<String, String>>>()
        val mediaUrl = "https://cdn.example.com/video.mp4"
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "https://www.instagram.com/reel/ABC123/",
                    mediaUrls = listOf(mediaUrl),
                    instagramApiStatus = "success_media",
                ),
            ),
            logger = com.ahdownload.core.common.DiagnosticLogger { _, type, _, _, context, _ ->
                loggedContexts += type to context
            },
            pageClient = RecordingTextClient { },
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.Instagram),
                operationId = operationId,
            ),
        )

        assertTrue(result is ResolverResult.Success)
        assertTrue(loggedContexts.isNotEmpty())
        assertTrue(loggedContexts.all { (_, context) -> context["operation_id"] == operationId })
        assertEquals(
            "success_media",
            loggedContexts.single { it.first == "SOCIAL_BROWSER_SESSION_RESULT" }
                .second["instagram_api_status"],
        )
    }

    @Test
    fun instagramInspectionFailureCountersAreIncludedInDiagnostics() = runTest {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "https://www.instagram.com/reel/ABC123/",
                    mediaUrls = emptyList(),
                    instagramApiStatus = "inspection_callback_missing",
                    inspectionAttemptCount = 4,
                    inspectionCallbackCount = 0,
                ),
            ),
            logger = com.ahdownload.core.common.DiagnosticLogger { _, type, _, _, context, _ ->
                events += type to context
            },
            pageClient = RecordingTextClient { },
        )

        val result = resolver.resolve(
            ResolverRequest(
                link = link(MediaPlatform.Instagram).copy(
                    originalUrl = "https://www.instagram.com/reel/ABC123/",
                    normalizedUrl = "https://www.instagram.com/reel/ABC123/",
                ),
                operationId = "instagram-inspection-test",
            ),
        )

        assertTrue(result is ResolverResult.Failure)
        val event = events.single { it.first == "SOCIAL_BROWSER_SESSION_RESULT" }
        assertEquals("inspection_callback_missing", event.second["instagram_api_status"])
        assertEquals("4", event.second["inspection_attempt_count"])
        assertEquals("0", event.second["inspection_callback_count"])
    }

    @Test
    fun observedVideoWithAudioTrackRemainsMuxed() = runTest {
        val url = "https://cdn.example.com/video.mp4"
        val resolver = InstagramResolverAdapter(
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

    @Test
    fun instagramVideoRequestRejectsImageThumbnailAndReportsTheReason() = runTest {
        val thumbnailUrl = "https://scontent.cdninstagram.com/o1/v/t16/f1/m999/thumbnail.jpg?stp=dst-jpg"
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val resolver = InstagramResolverAdapter(
            provider = FakeProvider(
                BrowserMediaSession(
                    platform = MediaPlatform.Instagram,
                    pageUrl = "https://www.instagram.com/reel/ABC123/",
                    mediaUrls = listOf(thumbnailUrl),
                ),
            ),
            logger = com.ahdownload.core.common.DiagnosticLogger { _, type, _, _, context, _ ->
                events += type to context
            },
            pageClient = RecordingTextClient { },
        )
        val videoLink = link(MediaPlatform.Instagram).copy(
            originalUrl = "https://www.instagram.com/reel/ABC123/",
            normalizedUrl = "https://www.instagram.com/reel/ABC123/",
            kind = com.ahdownload.domain.model.MediaKind.Video,
        )

        val result = resolver.resolve(ResolverRequest(link = videoLink))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(
            com.ahdownload.domain.resolver.FailureCode.NoCandidates,
            (result as ResolverResult.Failure).code,
        )
        val resolutionEvent = events.single { it.first == "SOCIAL_RESOLUTION_RESULT" }
        assertEquals("1", resolutionEvent.second["rejected_image_candidate_count"])
        assertEquals("0", resolutionEvent.second["video_candidate_count"])
    }

    @Test
    fun everySocialPlatformHasItsOwnFixedIdentityAdapter() = runTest {
        val provider = FakeProvider(
            BrowserMediaSession(
                platform = MediaPlatform.Instagram,
                pageUrl = "https://www.instagram.com/reel/ABC123/",
                mediaUrls = emptyList(),
            ),
        )
        val adapters: List<Pair<MediaPlatform, PlatformAdapter>> = listOf(
            MediaPlatform.Instagram to InstagramResolverAdapter(provider),
            MediaPlatform.Facebook to FacebookResolverAdapter(provider),
            MediaPlatform.TikTok to TikTokResolverAdapter(provider),
            MediaPlatform.X to XResolverAdapter(provider),
            MediaPlatform.Snapchat to SnapchatResolverAdapter(provider),
            MediaPlatform.Pinterest to PinterestResolverAdapter(provider),
            MediaPlatform.Reddit to RedditResolverAdapter(provider),
            MediaPlatform.Twitch to TwitchResolverAdapter(provider),
            MediaPlatform.Vimeo to VimeoResolverAdapter(provider),
        )

        assertEquals(9, adapters.map { it.first }.distinct().size)
        assertEquals(adapters.map { it.first }.toSet(), adapters.map { it.second.capability.platform }.toSet())

        adapters.forEach { (_, adapter) ->
            val result = adapter.resolve(
                ResolverRequest(link = link(MediaPlatform.YouTube)),
            )
            assertTrue(result is ResolverResult.Failure)
            assertEquals(
                com.ahdownload.domain.resolver.FailureCode.UnsupportedPlatform,
                (result as ResolverResult.Failure).code,
            )
        }
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
