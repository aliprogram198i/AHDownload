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
}
