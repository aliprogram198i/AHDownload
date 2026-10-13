package com.ahdownload.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConsoleMessageSanitizerTest {
    @Test
    fun removesUrlsAndQueryCredentials() {
        val result = BrowserConsoleMessageSanitizer.sanitize(
            "Failed to load https://example.test/player?access_token=topsecret net::ERR_FAILED",
        )

        assertTrue(result.contains("[url]"))
        assertFalse(result.contains("topsecret"))
        assertTrue(result.contains("net::ERR_FAILED"))
    }

    @Test
    fun redactsAuthorizationAndCredentialAssignments() {
        val result = BrowserConsoleMessageSanitizer.sanitize(
            "Authorization: Bearer abcdefghijklmnop; cookie=session_value; password=secret_value",
        )

        assertFalse(result.contains("abcdefghijklmnop"))
        assertFalse(result.contains("session_value"))
        assertFalse(result.contains("secret_value"))
        assertTrue(result.contains("[redacted]"))
    }

    @Test
    fun flattensControlCharactersAndCapsMessageLength() {
        val result = BrowserConsoleMessageSanitizer.sanitize("x".repeat(250) + "\nnext-line")

        assertEquals(220, result.length)
        assertFalse(result.contains("\n"))
    }

    @Test
    fun providesFallbackForEmptyMessage() {
        assertEquals(
            "Console error message unavailable",
            BrowserConsoleMessageSanitizer.sanitize(" \n\t "),
        )
    }
}
