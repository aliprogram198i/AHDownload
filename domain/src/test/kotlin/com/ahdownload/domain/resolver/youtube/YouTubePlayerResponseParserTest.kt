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
        assertTrue(result.candidates[0].format.hasVideo)
        assertTrue(result.candidates[0].format.hasAudio)
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
                  {"itag":"999","mimeType":"video/mp4; codecs=\"avc1.4D401F\"","signatureCipher":"url=https%3A%2F%2Fcdn.example.com%2Fsource%3Fitag%3D999&s=abc"}
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
    fun usesExactObservedBrowserUrlsToRecoverCipheredFormatMetadata() {
        val video1080Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&pot=browser-video"
        val video720Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=136&mime=video%2Fmp4&pot=browser-video"
        val audio160Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm&pot=browser-audio"
        val json = """
            {
              "videoDetails":{"title":"Cipher metadata test","lengthSeconds":"8"},
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "itag":"137",
                    "mimeType":"video/mp4; codecs=\"avc1.640028\"",
                    "width":1920,
                    "height":1080,
                    "fps":30,
                    "bitrate":4500000,
                    "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fitag%3D137&s=encrypted"
                  },
                  {
                    "itag":"136",
                    "mimeType":"video/mp4; codecs=\"avc1.4d401f\"",
                    "width":1280,
                    "height":720,
                    "fps":30,
                    "bitrate":2500000,
                    "cipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fitag%3D136&s=encrypted"
                  },
                  {
                    "itag":"251",
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "bitrate":160000,
                    "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fitag%3D251&s=encrypted"
                  }
                ]
              }
            }
        """.trimIndent()

        val result = parser.parsePlayerResponse(json, listOf(video1080Url, video720Url), listOf(audio160Url))

        assertTrue(result is ResolverResult.Success)
        val candidates = (result as ResolverResult.Success).candidates
        assertEquals(3, candidates.size)

        val video1080 = candidates.first { it.format.height == 1080 }
        assertEquals(video1080Url, video1080.sourceUrl)
        assertEquals("137", video1080.id)
        assertEquals(false, video1080.format.hasAudio)

        val video720 = candidates.first { it.format.height == 720 }
        assertEquals(video720Url, video720.sourceUrl)
        assertEquals("136", video720.id)

        val audio160 = candidates.first { it.format.kind == MediaKind.Audio }
        assertEquals(audio160Url, audio160.sourceUrl)
        assertEquals(160, audio160.format.bitrateKbps)
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
