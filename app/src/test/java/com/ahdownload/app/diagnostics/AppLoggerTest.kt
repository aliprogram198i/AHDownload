package com.ahdownload.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
    @Test
    fun redactsUrlsAndQuerySecrets() {
        val input = "https://example.com/video.mp4?sig=abc123&expires=999&foo=bar"
        val output = AppLogger.sanitizeForTesting(input)
        assertFalse(output.contains("https://example.com"))
        assertFalse(output.contains("sig=abc123"))
        assertFalse(output.contains("expires=999"))
        assertTrue(output.contains("[URL_REDACTED]"))
    }

    @Test
    fun redactsCredentialLikeFields() {
        val input = "Cookie: sessionid=secret-cookie
Authorization: Bearer top-secret-token"
        val output = AppLogger.sanitizeForTesting(input)
        assertFalse(output.contains("secret-cookie"))
        assertFalse(output.contains("top-secret-token"))
        assertTrue(output.contains("[SENSITIVE_REDACTED]"))
    }
}
