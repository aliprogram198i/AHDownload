package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.ResolverResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerResponseParserTest {
    private val parser = YouTubePlayerResponseParser()

    @Test
    fun parsesMetadataAndDirectFormats() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "videoDetails": {
                "title": "AHDownload Test",
                "lengthSeconds": "42",
                "thumbnail": {
                  "thumbnails": [
                    {"url":"https://img.example.com/1.jpg"},
                    {"url":"https://img.example.com/2.jpg"}
                  ]
                }
              },
              "streamingData": {
                "formats": [
                  {
                    "itag": "18",
                    "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                    "width": 640,
                    "height": 360,
                    "fps": 30,
                    "bitrate": 800000,
                    "contentLength": "1000",
                    "url": "https://cdn.example.com/18"
                  }
                ],
                "adaptiveFormats": [
                  {
                    "itag": "140",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000,
                    "contentLength": "2000",
                    "url": "https://cdn.example.com/140"
                  }
                ]
              }
            };
            </script>
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        assertEquals("AHDownload Test", result.title)
        assertEquals(42_000L, result.durationMs)
        assertEquals("https://img.example.com/2.jpg", result.thumbnailUrl)
        assertEquals(2, result.candidates.size)
        assertEquals(MediaKind.Video, result.candidates[0].format.kind)
        assertEquals(MediaKind.Audio, result.candidates[1].format.kind)
    }

    @Test
    fun rejectsMissingPlayerResponse() {
        val result = parser.parse("<html>blocked</html>")
        assertEquals(
            FailureCode.ResolverUnavailable,
            (result as ResolverResult.Failure).code,
        )
    }

    @Test
    fun ignoresFormatsWithoutDirectUrl() {
        val html = """
            var ytInitialPlayerResponse = {
              "videoDetails": {"title":"No Direct URL"},
              "streamingData": {
                "formats": [
                  {"itag":"999","mimeType":"video/mp4; codecs=\"avc1.4D401F\"","signatureCipher":"s=abc"}
                ]
              }
            };
        """.trimIndent()

        val result = parser.parse(html)
        assertEquals(
            FailureCode.NoCandidates,
            (result as ResolverResult.Failure).code,
        )
    }

    @Test
    fun parsesUrlEncodedPlayerResponse() {
        val json = """{"videoDetails":{"title":"Encoded"},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":"18","mimeType":"video/mp4","url":"https://cdn.example.com/encoded"}]}}"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8")
        val result = parser.parsePlayerResponse(encoded)
        assertTrue(result is ResolverResult.Success)
        assertEquals("Encoded", (result as ResolverResult.Success).title)
    }

    @Test
    fun parsesQuotedPlayerResponseMarker() {
        val json = """{"videoDetails":{"title":"Quoted"},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":"18","mimeType":"video/mp4","url":"https://cdn.example.com/quoted"}]}}"""
        val html = ""player_response":"" + json.replace(""", "\\"") + """

        val result = parser.parse(html)

        assertTrue(result is ResolverResult.Success)
        assertEquals("Quoted", (result as ResolverResult.Success).title)
    }
}
