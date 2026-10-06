package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.HttpTextClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerClientTest {
    @Test
    fun embeddedFallbackUsesEmbeddedClientContextAndHeaders() = runBlocking {
        val http = RecordingHttpClient()
        val client = YouTubePlayerClient(http)
        val html = """
            <script>
            var ytcfg = {
              "INNERTUBE_API_KEY": "test-key",
              "INNERTUBE_CONTEXT": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20260708.00.00",
                  "visitorData": "visitor-token",
                  "userAgent": "UA-Test"
                }
              }
            };
            </script>
            "INNERTUBE_API_KEY":"test-key"
            "INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20260708.00.00","visitorData":"visitor-token","userAgent":"UA-Test"}}
        """.trimIndent()

        http.response = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"Embedded"},"streamingData":{"formats":[{"itag":"18","mimeType":"video/mp4","url":"https://video.googlevideo.com/videoplayback?itag=18"}]}}"""

        val response = client.fetchEmbeddedPlayerResponse(
            html = html,
            videoUrl = "https://www.youtube.com/watch?v=abcdefghijk",
            operationId = "op-1",
        )

        assertEquals(http.response, response)
        assertTrue(http.body!!.contains(""clientName":"WEB_EMBEDDED_PLAYER""))
        assertTrue(http.body!!.contains(""thirdParty":{"embedUrl":"https://www.youtube.com/"}"))
        assertEquals("56", http.headers["X-YouTube-Client-Name"])
        assertEquals("2.20260708.00.00", http.headers["X-YouTube-Client-Version"])
        assertEquals("visitor-token", http.headers["X-Goog-Visitor-Id"])
        assertEquals("https://www.youtube.com/", http.headers["Origin"])
        assertEquals("https://www.youtube.com/", http.headers["Referer"])
    }

    private class RecordingHttpClient : HttpTextClient {
        var response: String = "{}"
        var body: String? = null
        var headers: Map<String, String> = emptyMap()

        override suspend fun get(url: String): String = ""

        override suspend fun postJson(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): String {
            this.body = body
            this.headers = headers
            return response
        }
    }
}
