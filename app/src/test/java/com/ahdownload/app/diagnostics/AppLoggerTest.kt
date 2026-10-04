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
        val input = "Cookie: sessionid=secret-cookie\nAuthorization: Bearer top-secret-token"
        val output = AppLogger.sanitizeForTesting(input)
        assertFalse(output.contains("secret-cookie"))
        assertFalse(output.contains("top-secret-token"))
        assertTrue(output.contains("[SENSITIVE_REDACTED]"))
    }

    @Test
    fun redactsBearerAndAdditionalQuerySecrets() {
        val input = """
            Authorization: Bearer super-secret-token
            refresh_token=refresh-secret
            https://cdn.example.com/file.mp4?signature=secret-signature&oe=private&safe=yes
        """.trimIndent()
        val output = AppLogger.sanitizeForTesting(input)

        assertFalse(output.contains("super-secret-token"))
        assertFalse(output.contains("refresh-secret"))
        assertFalse(output.contains("secret-signature"))
        assertFalse(output.contains("private"))
        assertTrue(output.contains("[SENSITIVE_REDACTED]"))
        assertTrue(output.contains("[URL_REDACTED]"))
    }
