package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeResolverTest {
    @Test
    fun resolvesVideoUsingInjectedClientAndFiltersRequestedKind() = runBlocking {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                var ytInitialPlayerResponse = {
                  "videoDetails": {"title":"Resolver Test","lengthSeconds":"10"},
                  "streamingData": {
                    "formats": [
                      {
                        "itag":"18",
                        "mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"",
                        "width":640,
                        "height":360,
                        "url":"https://cdn.example.com/18"
                      }
                    ],
                    "adaptiveFormats": [
                      {
                        "itag":"140",
                        "mimeType":"audio/mp4; codecs=\\"mp4a.40.2\\"",
                        "bitrate":128000,
                        "url":"https://cdn.example.com/140"
                      }
                    ]
                  }
                };
            """.trimIndent()
        }

        val resolver = YouTubeResolver(client)
        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/example",
                    normalizedUrl = "https://youtu.be/example",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
                requestedKind = MediaKind.Audio,
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        assertEquals("Resolver Test", result.title)
        assertEquals(1, result.candidates.size)
        assertEquals(MediaKind.Audio, result.candidates.single().format.kind)
    }

    @Test
    fun rejectsNonYouTubeRequest() = runBlocking {
        val resolver = YouTubeResolver(object : HttpTextClient {
            override suspend fun get(url: String): String = ""
        })

        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://example.com/video",
                    normalizedUrl = "https://example.com/video",
                    platform = MediaPlatform.Unknown,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertEquals(
            com.ahdownload.domain.resolver.FailureCode.UnsupportedPlatform,
            (result as ResolverResult.Failure).code,
        )
    }

    @Test
    fun fallsBackToYouTubePlayerApiWhenHtmlHasNoPlayerResponse() = runBlocking {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                {"INNERTUBE_API_KEY":"test-key","INNERTUBE_CONTEXT":{"client":{"clientName":"ANDROID","clientVersion":"19.09.37"}}}
            """.trimIndent()

            override suspend fun postJson(url: String, body: String): String = """
                {
                  "videoDetails":{"title":"Fallback Test","lengthSeconds":"8"},
                  "playabilityStatus":{"status":"OK"},
                  "streamingData":{"formats":[
                    {"itag":"18","mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"","width":640,"height":360,"url":"https://cdn.example.com/fallback"}
                  ]}
                }
            """.trimIndent()
        }

        val resolver = YouTubeResolver(client)
        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/fallback1",
                    normalizedUrl = "https://youtu.be/fallback1",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        assertEquals("Fallback Test", (result as ResolverResult.Success).title)
    }

}
