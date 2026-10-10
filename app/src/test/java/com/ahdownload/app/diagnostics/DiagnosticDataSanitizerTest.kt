package com.ahdownload.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDataSanitizerTest {
    @Test
    fun preservesSafeCorrelationMetadataButRedactsSecrets() {
        val sanitized = DiagnosticDataSanitizer.sanitizeContext(
            mapOf(
                "diagnostic_session_id" to "session-safe-123",
                "event_sequence" to "42",
                "api_key" to "private-api-key",
                "request_cookie" to "private-cookie",
                "source_url" to "https://media.example/video.mp4?token=private-token",
                "platform" to "YouTube",
            ),
        )

        assertEquals("session-safe-123", sanitized["diagnostic_session_id"])
        assertEquals("42", sanitized["event_sequence"])
        assertEquals("[REDACTED]", sanitized["api_key"])
        assertEquals("[REDACTED]", sanitized["request_cookie"])
        assertFalse(sanitized.values.joinToString(" ").contains("private-api-key"))
        assertFalse(sanitized.values.joinToString(" ").contains("private-cookie"))
        assertFalse(sanitized.values.joinToString(" ").contains("private-token"))
        assertFalse(sanitized.values.joinToString(" ").contains("media.example"))
        assertEquals("YouTube", sanitized["platform"])
    }

    @Test
    fun sanitizesSecretsAndFullUrlsInThrowableText() {
        val text = DiagnosticDataSanitizer.sanitizeText(
            "HTTP 403 token=hidden-value at https://media.example/videoplayback?sig=hidden-signature",
        )

        assertTrue(text.contains("HTTP 403"))
        assertFalse(text.contains("hidden-value"))
        assertFalse(text.contains("hidden-signature"))
        assertFalse(text.contains("media.example"))
    }
}
