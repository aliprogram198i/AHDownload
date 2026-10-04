package com.ahdownload.domain.analyzer

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkAnalyzerTest {
    private val analyzer = LinkAnalyzer()

    @Test
    fun recognizesYouTube() {
        val result = analyzer.analyze("https://youtu.be/example")
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals(MediaKind.Unknown, result?.kind)
    }

    @Test
    fun recognizesDirectVideo() {
        val result = analyzer.analyze("https://cdn.example.com/video.mp4")
        assertEquals(MediaPlatform.Unknown, result?.platform)
        assertEquals(MediaKind.Video, result?.kind)
    }

    @Test
    fun rejectsInvalidScheme() {
        assertNull(analyzer.analyze("ftp://example.com/video.mp4"))
    }

    @Test
    fun trimsInputWithoutChangingOriginal() {
        val result = analyzer.analyze("  https://instagram.com/reel/example  ")
        assertEquals("  https://instagram.com/reel/example  ", result?.originalUrl)
        assertEquals("https://instagram.com/reel/example", result?.normalizedUrl)
    }
}
