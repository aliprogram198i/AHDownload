package com.ahdownload.app.download

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.MediaSourceContext
import com.ahdownload.domain.validation.CandidateValidationResult
import com.ahdownload.domain.validation.CandidateValidator
import com.ahdownload.domain.validation.MediaProbe
import com.ahdownload.domain.validation.MediaProbeResult
import com.ahdownload.feature.home.HomeResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ValidationSessionCookieInjectionTest {
    @Test
    fun usesSameHostWebViewCookieForValidationWithoutPersistingIt() = runBlocking {
        val host = "video-cdg4-1.xx.fbcdn.net"
        val cookieUrl = "https://$host/"
        val cookieName = "ahdownload_validation_probe"
        val cookieValue = "ephemeral"
        val cookie = "$cookieName=$cookieValue"
        val cookieManager = CookieManager.getInstance()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        setCookieAndAwait(cookieManager, instrumentation, cookieUrl, "$cookie; Path=/; Secure")
        cookieManager.flush()
        try {
            assertTrue(
                "The test WebView cookie must be available for the media host",
                cookieManager.getCookie(cookieUrl).orEmpty().contains(cookie),
            )

            val observedHeaders = AtomicReference<Map<String, String>>(emptyMap())
            val probe = object : MediaProbe {
                override suspend fun probe(
                    url: String,
                    headers: Map<String, String>,
                    operationId: String?,
                    sourceContext: MediaSourceContext,
                ): MediaProbeResult {
                    observedHeaders.set(headers.toMap())
                    return MediaProbeResult(
                        statusCode = 206,
                        contentType = "video/mp4",
                        contentLengthBytes = 128L,
                        finalUrl = url,
                        method = "GET",
                        range = "bytes=0-0",
                    )
                }
            }
            val candidate = MediaCandidate(
                id = "facebook-browser-observed-test",
                sourceUrl = cookieUrl + "sample.mp4",
                format = MediaFormat(
                    id = "facebook-mp4-test",
                    kind = MediaKind.Video,
                    container = MediaContainer.Mp4,
                    hasVideo = true,
                    hasAudio = false,
                ),
                requestHeaders = mapOf("Referer" to "https://www.facebook.com/"),
                sessionCookieHost = host,
                sourceContext = MediaSourceContext.BROWSER_OBSERVED,
            )

            val result = HomeResolver(
                candidateValidator = CandidateValidator(probe = probe),
            ).validate(candidate, operationId = "op-facebook-cookie-validation")

            assertTrue(result is CandidateValidationResult.Valid)
            assertEquals(cookie, observedHeaders.get()["Cookie"])
            assertEquals("https://www.facebook.com/", observedHeaders.get()["Referer"])
            val accepted = result as CandidateValidationResult.Valid
            assertFalse(
                accepted.candidate.requestHeaders.keys.any { it.equals("Cookie", ignoreCase = true) },
            )
        } finally {
            setCookieAndAwait(
                cookieManager,
                instrumentation,
                cookieUrl,
                "$cookieName=; Path=/; Max-Age=0; Secure",
            )
            cookieManager.flush()
        }
    }

    private fun setCookieAndAwait(
        cookieManager: CookieManager,
        instrumentation: android.app.Instrumentation,
        url: String,
        value: String,
    ) {
        val latch = CountDownLatch(1)
        val success = AtomicReference<Boolean>()
        instrumentation.runOnMainSync {
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(url, value) { accepted ->
                success.set(accepted)
                latch.countDown()
            }
        }
        assertTrue("CookieManager did not finish updating the test cookie", latch.await(5, TimeUnit.SECONDS))
        assertEquals(true, success.get())
    }
}
