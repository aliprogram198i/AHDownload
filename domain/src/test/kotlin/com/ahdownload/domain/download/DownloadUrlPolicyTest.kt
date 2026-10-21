package com.ahdownload.domain.download

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadUrlPolicyTest {
    @Test
    fun acceptsWellFormedHttpsMediaUrls() {
        assertTrue(DownloadUrlPolicy.isSecureHttpsUrl("https://cdn.example/video.mp4"))
        assertTrue(DownloadUrlPolicy.isSecureHttpsUrl("HTTPS://cdn.example:8443/video.mp4?token=a%2Bb"))
    }

    @Test
    fun rejectsCleartextAndUnsupportedSchemes() {
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl("http://cdn.example/video.mp4"))
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl("ftp://cdn.example/video.mp4"))
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl("file:///tmp/video.mp4"))
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl("cdn.example/video.mp4"))
    }

    @Test
    fun rejectsMalformedUrlsAndEmbeddedCredentials() {
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl(""))
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl("https:///video.mp4"))
        assertFalse(DownloadUrlPolicy.isSecureHttpsUrl("https://user:password@cdn.example/video.mp4"))
    }
}
