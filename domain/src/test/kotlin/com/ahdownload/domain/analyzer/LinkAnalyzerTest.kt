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
    fun recognizesSupportedPlatforms() {
        assertEquals(
            MediaPlatform.Instagram,
            analyzer.analyze("https://www.instagram.com/reel/example/")?.platform,
        )
        assertEquals(
            MediaPlatform.Facebook,
            analyzer.analyze("https://fb.watch/example/")?.platform,
        )
        assertEquals(
            MediaPlatform.TikTok,
            analyzer.analyze("https://www.tiktok.com/@user/video/123")?.platform,
        )
        assertEquals(
            MediaPlatform.X,
            analyzer.analyze("https://x.com/user/status/123")?.platform,
        )
    }

    @Test
    fun recognizesDirectMediaByType() {
        assertEquals(
            MediaPlatform.DirectMedia,
            analyzer.analyze("https://cdn.example.com/video.mp4")?.platform,
        )
        assertEquals(
            MediaKind.Video,
            analyzer.analyze("https://cdn.example.com/video.mp4")?.kind,
        )
        assertEquals(
            MediaPlatform.DirectMedia,
            analyzer.analyze("https://cdn.example.com/audio.m4a")?.platform,
        )
        assertEquals(
            MediaKind.Audio,
            analyzer.analyze("https://cdn.example.com/audio.m4a")?.kind,
        )
        assertEquals(
            MediaPlatform.DirectMedia,
            analyzer.analyze("https://cdn.example.com/image.webp")?.platform,
        )
        assertEquals(
            MediaKind.Image,
            analyzer.analyze("https://cdn.example.com/image.webp")?.kind,
        )
    }

    @Test
    fun doesNotMisclassifyLookalikeHosts() {
        assertEquals(
            MediaPlatform.Unknown,
            analyzer.analyze("https://evil-youtube.com/video")?.platform,
        )
        assertEquals(
            MediaPlatform.Unknown,
            analyzer.analyze("https://instagram.com.evil.example/reel/123")?.platform,
        )
    }

    @Test
    fun recognizesCaseInsensitiveSchemeAndHost() {
        val result = analyzer.analyze("  HTTPS://WWW.YOUTUBE.COM/watch?v=123  ")
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals("  HTTPS://WWW.YOUTUBE.COM/watch?v=123  ", result?.originalUrl)
        assertEquals("HTTPS://WWW.YOUTUBE.COM/watch?v=123", result?.normalizedUrl)
    }

    @Test
    fun rejectsInvalidAndMalformedUrls() {
        assertNull(analyzer.analyze(""))
        assertNull(analyzer.analyze("   "))
        assertNull(analyzer.analyze("ftp://example.com/video.mp4"))
        assertNull(analyzer.analyze("not a url"))
        assertNull(analyzer.analyze("https:///missing-host"))
    }

    @Test
    fun keepsUnknownMediaKindForNonMediaPaths() {
        val result = analyzer.analyze("https://cdn.example.com/resource?id=123")
        assertEquals(MediaPlatform.Unknown, result?.platform)
        assertEquals(MediaKind.Unknown, result?.kind)
    }

    @Test
    fun trimsInputWithoutChangingOriginal() {
        val result = analyzer.analyze("  https://instagram.com/reel/example  ")
        assertEquals("  https://instagram.com/reel/example  ", result?.originalUrl)
        assertEquals("https://instagram.com/reel/example", result?.normalizedUrl)
    }
}
