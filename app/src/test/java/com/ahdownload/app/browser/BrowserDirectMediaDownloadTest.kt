package com.ahdownload.app.browser

import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.MediaContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDirectMediaDownloadTest {
    private val analyzer = LinkAnalyzer()

    @Test
    fun mimeTypeDetectsExtensionlessVideoAndPreservesSignedUrlExactly() {
        val url = "https://cdn.example.test/asset?token=keep%2Bme&expires=123"
        val link = BrowserDirectMediaDownload.mediaLink(url, "video/mp4; charset=binary", null, analyzer)

        assertEquals(MediaPlatform.DirectMedia, link?.platform)
        assertEquals(MediaKind.Video, link?.kind)
        assertEquals(url, link?.normalizedUrl)
    }

    @Test
    fun audioMimeTypeCreatesAudioOnlyDirectLink() {
        val url = "https://cdn.example.test/download?id=audio42"
        val link = BrowserDirectMediaDownload.mediaLink(url, "audio/mpeg", "attachment; filename=\"track.mp3\"", analyzer)

        assertEquals(MediaPlatform.DirectMedia, link?.platform)
        assertEquals(MediaKind.Audio, link?.kind)
        assertEquals(MediaContainer.Mp3, BrowserDirectMediaDownload.containerFor(url, "audio/mpeg", "attachment; filename=\"track.mp3\""))
    }

    @Test
    fun extensionFallbackOnlyAcceptsDirectMediaRatherThanSocialPageUrls() {
        val direct = BrowserDirectMediaDownload.mediaLink("https://files.example.test/clip.mp4?sig=abc", null, null, analyzer)
        val page = BrowserDirectMediaDownload.mediaLink("https://www.instagram.com/reel/abc123/", null, null, analyzer)

        assertEquals(MediaPlatform.DirectMedia, direct?.platform)
        assertEquals(MediaKind.Video, direct?.kind)
        assertNull(page)
    }

    @Test
    fun rejectsUnsupportedMimeTypesAndUnsafeSchemes() {
        assertNull(BrowserDirectMediaDownload.mediaLink("https://example.test/page", "text/html", null, analyzer))
        assertNull(BrowserDirectMediaDownload.mediaLink("https://example.test/clip.mp4", "text/html", null, analyzer))
        assertNull(BrowserDirectMediaDownload.mediaLink("https://example.test/document.mp4", "application/pdf", null, analyzer))
        assertNull(BrowserDirectMediaDownload.mediaLink("javascript:alert(1)", "video/mp4", null, analyzer))
        assertNull(BrowserDirectMediaDownload.mediaLink("file:///sdcard/clip.mp4", null, null, analyzer))
    }

    @Test
    fun headersRejectCrLfAndRequireHttpReferer() {
        val headers = BrowserDirectMediaDownload.requestHeaders(
            referer = "javascript:alert(1)",
            userAgent = "AHDownloadTest\r\nCookie: injected",
        )

        assertTrue(headers.isEmpty())
        val safe = BrowserDirectMediaDownload.requestHeaders("https://site.example/watch?id=secret#section", "Browser/1")
        assertFalse(safe.isEmpty())
        assertEquals("https://site.example/watch", safe["Referer"])
    }

    @Test
    fun displayNameUsesSafeFilenameFromContentDisposition() {
        val name = BrowserDirectMediaDownload.displayName(
            "https://cdn.example.test/file?token=secret",
            "attachment; filename*=UTF-8''my%20video.mp4",
        )

        assertEquals("my video.mp4", name)
    }
}
