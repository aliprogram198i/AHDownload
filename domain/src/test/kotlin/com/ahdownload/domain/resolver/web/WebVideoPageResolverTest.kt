package com.ahdownload.domain.resolver.web

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
    fun rejectsLocalPageBeforeMakingNetworkRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebVideoPageResolver(client = client(requests, emptyList()))

        val result = resolver.resolve(request("http://127.0.0.1/private"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.InvalidRequest, (result as ResolverResult.Failure).code)
        assertTrue(requests.isEmpty())
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
