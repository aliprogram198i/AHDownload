package com.ahdownload.domain.resolver.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageMediaParserTest {
    @Test
    fun preservesLiteralPlusCharactersInMediaUrls() {
        val html = """
            <meta property="og:video" content="https://cdn.example.com/video+mix.mp4?token=a+b&amp;part=1">
        """.trimIndent()

        val result = WebPageMediaParser.parse(
            html = html,
            baseUrl = "https://example.com/post",
        )

        assertEquals(
            "https://cdn.example.com/video+mix.mp4?token=a+b&part=1",
            result.mediaUrls.single(),
        )
    }

    @Test
    fun decodesPercentEncodedUrlValuesWithoutTouchingLiteralPlus() {
        val html = """
            <meta property="og:video" content="https%3A%2F%2Fcdn.example.com%2Fvideo%2Bmix.mp4%3Fpart%3D1%26token%3Da%2Bb">
        """.trimIndent()

        val result = WebPageMediaParser.parse(
            html = html,
            baseUrl = "https://example.com/post",
        )

        assertTrue(result.mediaUrls.single().startsWith("https://cdn.example.com/"))
        assertEquals(
            "https://cdn.example.com/video+mix.mp4?part=1&token=a+b",
            result.mediaUrls.single(),
        )
    }

    @Test
    fun keepsHlsAndDashManifestUrls() {
        val html = """
            <meta property="og:video" content="https://cdn.example.com/master.m3u8?token=a+b">
            <source src="https://cdn.example.com/stream.mpd?session=1">
        """.trimIndent()

        val result = WebPageMediaParser.parse(
            html = html,
            baseUrl = "https://example.com/post",
        )

        assertEquals(
            setOf(
                "https://cdn.example.com/master.m3u8?token=a+b",
                "https://cdn.example.com/stream.mpd?session=1",
            ),
            result.mediaUrls.toSet(),
        )
    }

    @Test
    fun ignoresInstagramCdnThumbnailWhenDiscoveringVideoSources() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://scontent.cdninstagram.com/o1/v/t16/f1/m999/thumbnail.jpg?stp=dst-jpg">
            </head><body></body></html>
        """.trimIndent()

        val result = WebPageMediaParser.parse(
            html = html,
            baseUrl = "https://www.instagram.com/reel/ABC123/",
        )

        assertTrue(result.mediaUrls.isEmpty())
    }

    @Test
    fun extractsInstagramEscapedVideoUrlFromApplicationState() {
        val html = """
            <script type="application/json">
              {
                "video_versions": [{
                  "url": "https:\/\/instagram.fsgn5-21.fna.fbcdn.net\/o1\/v\/t2\/f2\/m367\/AQExampleVideo.mp4?stp=dst-jpg&_nc_sid=9ca052",
                  "width": 1080,
                  "height": 1920
                }]
              }
            </script>
        """.trimIndent()

        val result = WebPageMediaParser.parse(
            html = html,
            baseUrl = "https://www.instagram.com/reel/ABC123/",
        )

        assertEquals(
            "https://instagram.fsgn5-21.fna.fbcdn.net/o1/v/t2/f2/m367/AQExampleVideo.mp4?stp=dst-jpg&_nc_sid=9ca052",
            result.mediaUrls.single(),
        )
    }

    @Test
    fun extractsInstagramPlaybackUrlWithoutOpenGraphTag() {
        val html = """
            <script type="application/json">
              {"playback_url":"https:\/\/scontent.example.fbcdn.net\/o1\/v\/t16\/f1\/m999\/AQPlayback.mp4?x=1\u0026y=2"}
            </script>
        """.trimIndent()

        val result = WebPageMediaParser.parse(
            html = html,
            baseUrl = "https://www.instagram.com/reel/ABC123/",
        )

        assertEquals(
            "https://scontent.example.fbcdn.net/o1/v/t16/f1/m999/AQPlayback.mp4?x=1&y=2",
            result.mediaUrls.single(),
        )
    }
}
