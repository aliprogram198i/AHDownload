package com.ahdownload.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegManifestDiagnosticTest {
    @Test
    fun redactsSignedUrlsCookiesAndAuthorizationValues() {
        val output = sanitizeFfmpegDiagnostic(
            """
            HTTP error 403 Forbidden while opening https://cdn.example/playlist.m3u8?token=secret-token
            Cookie: session-secret
            Authorization: Bearer private-credential
            """.trimIndent(),
        )

        assertTrue(output.contains("HTTP error 403"))
        assertTrue(output.contains("[url redacted]"))
        assertFalse(output.contains("secret-token"))
        assertFalse(output.contains("session-secret"))
        assertFalse(output.contains("private-credential"))
        assertFalse(output.contains("Bearer"))
    }

    @Test
    fun diagnosticOutputIsBounded() {
        val raw = (1..40).joinToString("\n") { "HTTP error 403 " + "x".repeat(500) }

        val output = sanitizeFfmpegDiagnostic(raw)

        assertTrue(output.length <= 1000)
        assertTrue(output.contains("HTTP error 403"))
    }

    @Test
    fun blankDiagnosticHasExplicitFallback() {
        assertTrue(sanitizeFfmpegDiagnostic(" \n ").contains("no FFmpeg diagnostic output"))
    }
}
