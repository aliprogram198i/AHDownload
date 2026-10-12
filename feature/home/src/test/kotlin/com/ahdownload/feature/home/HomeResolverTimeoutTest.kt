package com.ahdownload.feature.home

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeResolverTimeoutTest {
    @Test
    fun returnsTerminalTimeoutFailureWhenBrowserSessionNeverCompletes() = runBlocking {
        var providerCalled = false
        val events = mutableListOf<String>()
        val provider = object : BrowserMediaSessionProvider {
            override suspend fun snapshot(
                url: String,
                platform: MediaPlatform,
            ): BrowserMediaSession {
                providerCalled = true
                delay(10_000L)
                return BrowserMediaSession(platform = platform, pageUrl = url)
            }
        }
        val resolver = HomeResolver(
            logger = DiagnosticLogger { _, type, _, _, _, _ -> events += type },
            browserMediaSessionProvider = provider,
            resolveTimeoutMs = 1_000L,
        )
        val link = MediaLink(
            originalUrl = "https://www.instagram.com/reel/ABC123/",
            normalizedUrl = "https://www.instagram.com/reel/ABC123/",
            platform = MediaPlatform.Instagram,
            kind = MediaKind.Video,
        )

        val result = resolver.resolve(link, operationId = "timeout-test")

        assertTrue("The delayed browser provider must be reached", providerCalled)
        val failure = result as? ResolverResult.Failure
        assertTrue("A stalled resolver must return a terminal failure", failure != null)
        assertEquals(FailureCode.ResolverTimeout, failure?.code)
        assertTrue(failure?.message.orEmpty().contains("تحقق من اتصال الإنترنت"))
        assertTrue("Timeout must be recorded for diagnostics", events.contains("RESOLUTION_TIMEOUT"))
    }
}
