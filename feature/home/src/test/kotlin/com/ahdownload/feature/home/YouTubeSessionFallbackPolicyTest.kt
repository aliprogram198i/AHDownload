package com.ahdownload.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSessionFallbackPolicyTest {
    @Test
    fun loadsEmbeddedFallbackWhenMediaWasObservedButPlayerMetadataIsMissing() {
        assertTrue(
            shouldLoadYouTubeEmbeddedFallback(
                attempt = 5,
                observedMediaCount = 3,
                hasPlayerResponse = false,
                embeddedFallbackLoaded = false,
            ),
        )
    }

    @Test
    fun doesNotLoadEmbeddedFallbackWhenPlayerMetadataWasCaptured() {
        assertFalse(
            shouldLoadYouTubeEmbeddedFallback(
                attempt = 5,
                observedMediaCount = 3,
                hasPlayerResponse = true,
                embeddedFallbackLoaded = false,
            ),
        )
    }

    @Test
    fun doesNotLoadEmbeddedFallbackMoreThanOnce() {
        assertFalse(
            shouldLoadYouTubeEmbeddedFallback(
                attempt = 10,
                observedMediaCount = 3,
                hasPlayerResponse = false,
                embeddedFallbackLoaded = true,
            ),
        )
    }

    @Test
    fun preservesExistingEmptyMediaFallbackAtAttemptFive() {
        assertTrue(
            shouldLoadYouTubeEmbeddedFallback(
                attempt = 5,
                observedMediaCount = 0,
                hasPlayerResponse = false,
                embeddedFallbackLoaded = false,
            ),
        )
    }

    @Test
    fun doesNotLoadEmbeddedFallbackWhenPlayerResponseRequiresSignInOrAgeVerification() {
        assertFalse(
            shouldLoadYouTubeEmbeddedFallback(
                attempt = 5,
                observedMediaCount = 0,
                hasPlayerResponse = true,
                embeddedFallbackLoaded = false,
                requiresAuthentication = true,
            ),
        )
    }

}
