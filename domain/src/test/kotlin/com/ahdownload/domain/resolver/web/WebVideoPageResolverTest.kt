package com.ahdownload.domain.resolver.web

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class WebVideoPageResolverTest {
    @Test
    fun discoversHtml5SourceAndPreservesPageRefererAndQuality() = runBlocking {
        val requests = mutableListOf<Request>()
        val html = """
            <!doctype html>
            <html><head><title>Demo Movie</title></head>
            <body><video controls><source src="/media/movie.mp4" type="video/mp4" data-quality="720p"></video></body></html>
        """.trimIndent()
        val resolver = WebVideoPageResolver(client = client(requests, listOf(
            response(200, mapOf("Content-Type" to "text/html; charset=utf-8"), html),
            response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "12345")),
        )))

        val result = resolver.resolve(request("https://site.example/watch/episode-1"))

        assertTrue(result is ResolverResult.Success)
        val success = result as ResolverResult.Success
        assertEquals("Demo Movie", success.title)
        val candidate = success.candidates.single()
        assertEquals("https://site.example/media/movie.mp4", candidate.sourceUrl)
        assertEquals(MediaKind.Video, candidate.format.kind)
        assertEquals(720, candidate.format.height)
        assertEquals(MediaContainer.Mp4, candidate.format.container)
        assertFalse(candidate.streamingManifest)
        assertEquals("https://site.example/watch/episode-1", candidate.requestHeaders["Referer"])
        assertEquals("GET", requests[0].method)
        assertEquals("HEAD", requests[1].method)
    }

    @Test
    fun discoversHlsManifestFromOpenGraphVideoMetadata() = runBlocking {
        val requests = mutableListOf<Request>()
        val html = """<html><head><meta property="og:video" content="https://cdn.example.com/hls/master.m3u8"></head></html>"""
        val resolver = WebVideoPageResolver(client = client(requests, listOf(
            response(200, mapOf("Content-Type" to "text/html"), html),
            response(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to "640")),
        )))

        val result = resolver.resolve(request("https://site.example/watch"))

        assertTrue(result is ResolverResult.Success)
        val candidate = (result as ResolverResult.Success).candidates.single()
        assertTrue(candidate.sourceUrl.endsWith(".m3u8"))
        assertTrue(candidate.streamingManifest)
        assertEquals(MediaKind.Video, candidate.format.kind)
        assertEquals(MediaContainer.Mkv, candidate.format.container)
        assertEquals(null, candidate.format.fileSizeBytes)
    }

    @Test
    fun discoversArchiveOrgDownloadFileFromPublicMetadataApi() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(
            client = client(
                requests,
                listOf(
                    response(200, mapOf("Content-Type" to "text/html"), "<html><title>Moon</title></html>"),
                    response(
                        200,
                        mapOf("Content-Type" to "application/json"),
                        """{"files":[{"name":"A_Trip_to_the_Moon.mp4","size":"1024","format":"MPEG4"},{"name":"metadata.xml","size":"200"}]}""",
                    ),
                    response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "1024")),
                ),
            ),
        )

        val result = resolver.resolve(request("https://archive.org/details/A_Trip_to_the_Moon"))

        assertTrue(result is ResolverResult.Success)
        val candidate = (result as ResolverResult.Success).candidates.single()
        assertEquals(
            "https://archive.org/download/A_Trip_to_the_Moon/A_Trip_to_the_Moon.mp4",
            candidate.sourceUrl,
        )
        assertFalse(candidate.streamingManifest)
        assertTrue(requests[1].url.encodedPath.startsWith("/metadata/"))
    }

    @Test
    fun discoversPeerTubeManifestFromPublicVideoApi() = runBlocking {
        val requests = mutableListOf<Request>()
        val videoId = "64cda44b-f748-42f4-a0c4-9a8be8e4f16a"
        val playlistUrl = "https://video.blender.org/static/streaming-playlists/hls/$videoId/master.m3u8"
        val resolver = WebVideoPageResolver(
            client = client(
                requests,
                listOf(
                    response(200, mapOf("Content-Type" to "text/html"), "<html><title>PeerTube</title></html>"),
                    response(
                        200,
                        mapOf("Content-Type" to "application/json"),
                        """{"streamingPlaylists":[{"playlistUrl":"$playlistUrl"}]}""",
                    ),
                    response(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to "640")),
                ),
            ),
        )

        val result = resolver.resolve(request("https://video.blender.org/w/$videoId"))

        assertTrue(result is ResolverResult.Success)
        val candidate = (result as ResolverResult.Success).candidates.single()
        assertEquals(playlistUrl, candidate.sourceUrl)
        assertTrue(candidate.streamingManifest)
        assertTrue(requests[1].url.encodedPath.startsWith("/api/v1/videos/"))
    }


    @Test
    fun fallsBackToLiveBrowserMediaWhenHtmlOnlyExposesSmallManifest() = runBlocking {
        val requests = mutableListOf<Request>()
        val pageUrl = "https://site.example/watch/player"
        val manifestUrl = "https://cdn.example.com/stream/master.m3u8"
        val actualVideoUrl = "https://cdn.example.com/media/actual-video.mp4"
        var providerCalled = false
        val html = """<html><head><title>Example video</title><meta property="og:video" content="$manifestUrl"></head></html>"""
        val browser = object : BrowserMediaSessionProvider {
            override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession {
                providerCalled = true
                assertEquals(pageUrl, url)
                assertEquals(MediaPlatform.Web, platform)
                return BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = url,
                    title = "Actual video",
                    mediaUrls = listOf(actualVideoUrl),
                    requestHeadersByUrl = mapOf(actualVideoUrl to mapOf("Origin" to "https://site.example")),
                )
            }
        }
        val resolver = WebVideoPageResolver(
            client = client(requests, listOf(
                response(200, mapOf("Content-Type" to "text/html"), html),
                response(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to "4096")),
                response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "26000000")),
            )),
            browserMediaSessionProvider = browser,
        )

        val result = resolver.resolve(request(pageUrl))

        assertTrue(result is ResolverResult.Success)
        assertTrue("Browser fallback should run for manifest-only static results", providerCalled)
        val candidates = (result as ResolverResult.Success).candidates
        assertEquals(2, candidates.size)
        val manifest = candidates.single { it.streamingManifest }
        val progressive = candidates.single { !it.streamingManifest }
        assertEquals(null, manifest.format.fileSizeBytes)
        assertEquals(actualVideoUrl, progressive.sourceUrl)
        assertEquals(26_000_000L, progressive.format.fileSizeBytes)
        assertEquals("Actual video", (result as ResolverResult.Success).title)
    }

    @Test
    fun rejectsImagePayloadEvenWhenHtmlAdvertisesMp4AndUsesRealBrowserMedia() = runBlocking {
        val requests = mutableListOf<Request>()
        val pageUrl = "https://site.example/watch"
        val placeholderUrl = "https://cdn.example.com/assets/placeholder.mp4"
        val actualVideoUrl = "https://cdn.example.com/media/actual-video.mp4"
        var providerCalled = false
        val html = """<html><video><source src="$placeholderUrl" type="video/mp4"></video></html>"""
        val browser = object : BrowserMediaSessionProvider {
            override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession {
                providerCalled = true
                return BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = url,
                    title = "Actual video",
                    mediaUrls = listOf(actualVideoUrl),
                )
            }
        }
        val resolver = WebVideoPageResolver(
            client = client(requests, listOf(
                response(200, mapOf("Content-Type" to "text/html"), html),
                response(200, mapOf("Content-Type" to "image/jpeg", "Content-Length" to "4096")),
                response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "26000000")),
            )),
            browserMediaSessionProvider = browser,
        )

        val result = resolver.resolve(request(pageUrl))

        assertTrue(result is ResolverResult.Success)
        assertTrue(providerCalled)
        val candidates = (result as ResolverResult.Success).candidates
        assertEquals(1, candidates.size)
        assertEquals(actualVideoUrl, candidates.single().sourceUrl)
        assertEquals(26_000_000L, candidates.single().format.fileSizeBytes)
    }

    @Test
    fun suppressesTinyBrowserObservedProgressiveClipsWhenRealVideoSizeIsKnown() = runBlocking {
        val requests = mutableListOf<Request>()
        val pageUrl = "https://site.example/watch"
        val tinyClip = "https://cdn.example.com/ads/short-clip.mp4"
        val actualVideo = "https://cdn.example.com/media/episode.mp4"
        val browser = object : BrowserMediaSessionProvider {
            override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession =
                BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = url,
                    title = "Episode",
                    mediaUrls = listOf(tinyClip, actualVideo),
                )
        }
        val resolver = WebVideoPageResolver(
            client = client(requests, listOf(
                response(200, mapOf("Content-Type" to "text/html"), "<html><title>Episode</title><video></video></html>"),
                response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "67000")),
                response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "26000000")),
            )),
            browserMediaSessionProvider = browser,
        )

        val result = resolver.resolve(request(pageUrl))

        assertTrue(result is ResolverResult.Success)
        val candidates = (result as ResolverResult.Success).candidates
        assertEquals(1, candidates.size)
        assertEquals(actualVideo, candidates.single().sourceUrl)
        assertEquals(26_000_000L, candidates.single().format.fileSizeBytes)
    }

    @Test
    fun reportsNoCandidatesForHtmlWithoutRecognizedVideoSources() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(client = client(requests, listOf(
            response(200, mapOf("Content-Type" to "text/html"), "<html><title>Article</title><p>Text only</p></html>"),
        )))

        val result = resolver.resolve(request("https://site.example/article"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
        assertEquals(1, requests.size)
    }

    @Test
    fun fallsBackToBrowserObservedMediaWhenPageHtmlHasNoSource() = runBlocking {
        val requests = mutableListOf<Request>()
        val pageUrl = "https://site.example/watch/dynamic"
        val mediaUrl = "https://cdn.example.com/media/actual-video.mp4"
        var providerCalled = false
        val browser = object : BrowserMediaSessionProvider {
            override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession {
                providerCalled = true
                assertEquals(pageUrl, url)
                assertEquals(MediaPlatform.Web, platform)
                return BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = url,
                    title = "Dynamic Player",
                    mediaUrls = listOf(mediaUrl),
                    requestHeadersByUrl = mapOf(
                        mediaUrl to mapOf("Origin" to "https://site.example"),
                    ),
                )
            }
        }
        val resolver = WebVideoPageResolver(
            client = client(requests, listOf(
                response(200, mapOf("Content-Type" to "text/html"), "<html><title>Dynamic</title><video id='player'></video></html>"),
                response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "9999")),
            )),
            browserMediaSessionProvider = browser,
        )

        val result = resolver.resolve(request(pageUrl))

        assertTrue(result is ResolverResult.Success)
        assertTrue(providerCalled)
        val success = result as ResolverResult.Success
        assertEquals("Dynamic Player", success.title)
        val candidate = success.candidates.single()
        assertEquals(mediaUrl, candidate.sourceUrl)
        assertEquals("https://site.example", candidate.requestHeaders["Origin"])
        assertEquals(pageUrl, candidate.requestHeaders["Referer"])
        assertEquals(MediaContainer.Mp4, candidate.format.container)
    }

    @Test
    fun fallsBackToBrowserObservedMediaWhenPageFetchRedirectLoops() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context }
        val requests = mutableListOf<Request>()
        val pageUrl = "https://site.example/watch"
        val mediaUrl = "https://cdn.example.com/media/redirect-loop-fallback.mp4"
        var providerCalled = false
        val browser = object : BrowserMediaSessionProvider {
            override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession {
                providerCalled = true
                assertEquals(pageUrl, url)
                assertEquals(MediaPlatform.Web, platform)
                return BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = url,
                    title = "Browser fallback video",
                    mediaUrls = listOf(mediaUrl),
                )
            }
        }
        val resolver = WebVideoPageResolver(
            logger = logger,
            client = client(
                requests,
                listOf(
                    response(301, mapOf("Location" to "/watch")),
                    response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "9999")),
                ),
            ),
            browserMediaSessionProvider = browser,
        )

        val result = resolver.resolve(request(pageUrl))

        assertTrue(result is ResolverResult.Success)
        assertTrue(providerCalled)
        val success = result as ResolverResult.Success
        assertEquals("Browser fallback video", success.title)
        assertEquals(mediaUrl, success.candidates.single().sourceUrl)
        assertTrue(events.any { (type, context) ->
            type == "WEB_VIDEO_REDIRECT_LOOP_BROWSER_FALLBACK" &&
                context["failure_category"] == "REDIRECT_LOOP" &&
                context["fallback"] == "BROWSER_MEDIA_SESSION"
        })
        assertEquals(2, requests.size)
    }

    @Test
    fun upgradesInitialHttpPageUrlToHttpsBeforeRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(client = client(requests, listOf(
            response(200, mapOf("Content-Type" to "text/html"), "<html><title>Plain page</title></html>"),
        )))

        val result = resolver.resolve(request("http://site.example/watch/episode-1"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
        assertEquals(1, requests.size)
        assertEquals("https://site.example/watch/episode-1", requests.single().url.toString())
    }

    @Test
    fun upgradesHttpMediaSourceToHttpsBeforeProbingIt() = runBlocking {
        val requests = mutableListOf<Request>()
        val html = """
            <html><head><title>Media Page</title></head>
            <body><video><source src="http://cdn.example.com/media/movie.mp4" type="video/mp4"></video></body></html>
        """.trimIndent()
        val resolver = WebVideoPageResolver(client = client(requests, listOf(
            response(200, mapOf("Content-Type" to "text/html"), html),
            response(200, mapOf("Content-Type" to "video/mp4", "Content-Length" to "12345")),
        )))

        val result = resolver.resolve(request("https://site.example/watch/movie"))

        assertTrue(result is ResolverResult.Success)
        assertEquals(2, requests.size)
        assertEquals("https://cdn.example.com/media/movie.mp4", requests[1].url.toString())
        assertEquals(
            "https://cdn.example.com/media/movie.mp4",
            (result as ResolverResult.Success).candidates.single().sourceUrl,
        )
    }

    @Test
    fun upgradesHttpRedirectTargetToHttpsBeforeFollowingIt() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(client = client(requests, listOf(
            response(301, mapOf("Location" to "http://site.example/watch/final")),
            response(200, mapOf("Content-Type" to "text/html"), "<html><title>Final page</title></html>"),
        )))

        val result = resolver.resolve(request("https://site.example/watch/start"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
        assertEquals(2, requests.size)
        assertEquals("https://site.example/watch/start", requests[0].url.toString())
        assertEquals("https://site.example/watch/final", requests[1].url.toString())
    }

    @Test
    fun detectsRedirectLoopsAndReportsSpecificFailureWithoutLoggingUrls() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context }
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(
            logger = logger,
            client = client(
                requests,
                listOf(
                    response(301, mapOf("Location" to "/step")),
                    response(301, mapOf("Location" to "/watch")),
                ),
            ),
        )

        val result = resolver.resolve(request("https://site.example/watch"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.ResolverUnavailable, (result as ResolverResult.Failure).code)
        assertEquals(2, requests.size)
        assertTrue(events.any { (type, context) ->
            type == "WEB_VIDEO_REDIRECT_LOOP_DETECTED" &&
                context["failure_category"] == "REDIRECT_LOOP"
        })
        assertTrue(events.any { (type, context) ->
            type == "WEB_VIDEO_PAGE_FAILED" &&
                context["failure_category"] == "REDIRECT_LOOP"
        })
        val serializedEvents = events.joinToString(" ") { (type, context) -> "$type $context" }
        assertFalse(serializedEvents.contains("https://"))
        assertFalse(serializedEvents.contains("/watch"))
        assertFalse(serializedEvents.contains("/step"))
    }

    @Test
    fun followsSixUniqueRedirectsAndParsesTheFinalPage() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context }
        val requests = mutableListOf<Request>()
        val locations = listOf("/one", "/two", "/three", "/four", "/five", "/six")
        val replies = locations.map { location -> response(301, mapOf("Location" to location)) } +
            response(200, mapOf("Content-Type" to "text/html"), "<html><title>Final</title><p>No media</p></html>")
        val resolver = WebVideoPageResolver(
            logger = logger,
            client = client(requests, replies),
        )

        val result = resolver.resolve(request("https://site.example/start"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
        assertEquals(7, requests.size)
        assertEquals("/six", requests.last().url.encodedPath)
        assertFalse(events.any { (type, _) -> type == "WEB_VIDEO_PAGE_FAILED" })
    }

    @Test
    fun classifiesRedirectLimitSeparatelyFromRedirectLoops() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val logger = DiagnosticLogger { _, type, _, _, context, _ -> events += type to context }
        val requests = mutableListOf<Request>()
        val locations = listOf(
            "/one", "/two", "/three", "/four", "/five",
            "/six", "/seven", "/eight", "/nine",
        )
        val replies = locations.map { location -> response(301, mapOf("Location" to location)) }
        val resolver = WebVideoPageResolver(
            logger = logger,
            client = client(requests, replies),
        )

        val result = resolver.resolve(request("https://site.example/start"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.ResolverUnavailable, (result as ResolverResult.Failure).code)
        assertEquals(9, requests.size)
        assertEquals("/eight", requests.last().url.encodedPath)
        assertTrue(events.any { (type, context) ->
            type == "WEB_VIDEO_REDIRECT_LIMIT_EXCEEDED" &&
                context["failure_category"] == "REDIRECT_LIMIT_EXCEEDED" &&
                context["redirect_count"] == "8"
        })
        assertTrue(events.any { (type, context) ->
            type == "WEB_VIDEO_PAGE_FAILED" &&
                context["failure_category"] == "REDIRECT_LIMIT_EXCEEDED"
        })
    }

    @Test
    fun rejectsLocalPageBeforeMakingNetworkRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(client = client(requests, emptyList()))

        val result = resolver.resolve(request("http://127.0.0.1/private"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.InvalidRequest, (result as ResolverResult.Failure).code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun recordsNetworkFailureCategoryWithoutPersistingTheRequestedUrl() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val logger = DiagnosticLogger { _, type, _, _, context, _ ->
            events += type to context
        }
        val requests = mutableListOf<Request>()
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                throw UnknownHostException("DNS lookup failed")
            }
            .build()
        val inputUrl = "https://private.example/watch?token=never-log"
        val resolver = WebVideoPageResolver(logger = logger, client = failingClient)

        val result = resolver.resolve(request(inputUrl))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.ResolverUnavailable, (result as ResolverResult.Failure).code)
        assertEquals(1, requests.size)
        val requestEvent = events.lastOrNull { it.first == "HTTP_REQUEST" }
        assertTrue(requestEvent != null)
        assertEquals("FAILED", requestEvent!!.second["network_outcome"])
        assertEquals("DNS_FAILURE", requestEvent.second["failure_category"])
        assertEquals("PAGE_FETCH", requestEvent.second["request_stage"])
        assertTrue(events.any { (type, context) ->
            type == "WEB_VIDEO_PAGE_FAILED" &&
                context["failure_stage"] == "PAGE_FETCH" &&
                context["failure_category"] == "DNS_FAILURE"
        })
        val serializedEvents = events.joinToString(" ") { (type, context) -> "$type $context" }
        assertFalse(serializedEvents.contains("https://"))
        assertFalse(serializedEvents.contains("/watch"))
        assertFalse(serializedEvents.contains("never-log"))
    }

    private fun request(url: String) = ResolverRequest(
        link = MediaLink(
            originalUrl = url,
            normalizedUrl = url,
            platform = MediaPlatform.Web,
            kind = MediaKind.Unknown,
        ),
        operationId = "web-video-test",
    )

    private fun client(requests: MutableList<Request>, replies: List<ResponseSpec>): OkHttpClient {
        val remaining = replies.toMutableList()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                val spec = remaining.removeAt(0)
                val builder = Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(spec.code)
                    .message("test")
                    .body(spec.body.toResponseBody())
                spec.headers.forEach { (name, value) -> builder.header(name, value) }
                builder.build()
            }
            .build()
    }

    private fun response(code: Int, headers: Map<String, String>, body: String = "") =
        ResponseSpec(code, headers, body)

    private data class ResponseSpec(val code: Int, val headers: Map<String, String>, val body: String = "")
}
