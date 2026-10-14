package com.ahdownload.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationCallbackGuardTest {
    @Test
    fun acceptsCallbackForTheActiveNavigationAndUrl() {
        assertTrue(
            BrowserNavigationCallbackGuard.isCurrent(
                callbackNavigationId = "navigation-2",
                activeNavigationId = "navigation-2",
                callbackUrl = "https://example.com/watch?id=2",
                activeWebViewUrl = "https://example.com/watch?id=2",
            ),
        )
    }

    @Test
    fun rejectsCallbackWhenNavigationHasBeenSuperseded() {
        assertFalse(
            BrowserNavigationCallbackGuard.isCurrent(
                callbackNavigationId = "navigation-1",
                activeNavigationId = "navigation-2",
                callbackUrl = "https://example.com/watch",
                activeWebViewUrl = "https://example.com/watch",
            ),
        )
    }

    @Test
    fun rejectsCallbackWhenWebViewAlreadyDisplaysAnotherUrl() {
        assertFalse(
            BrowserNavigationCallbackGuard.isCurrent(
                callbackNavigationId = "navigation-2",
                activeNavigationId = "navigation-2",
                callbackUrl = "https://old.example.com/",
                activeWebViewUrl = "https://new.example.com/",
            ),
        )
    }

    @Test
    fun rejectsBlankOrMissingNavigationContext() {
        assertFalse(
            BrowserNavigationCallbackGuard.isCurrent(
                callbackNavigationId = "",
                activeNavigationId = "navigation-2",
                callbackUrl = "https://example.com/",
                activeWebViewUrl = "https://example.com/",
            ),
        )
        assertFalse(
            BrowserNavigationCallbackGuard.isCurrent(
                callbackNavigationId = "navigation-2",
                activeNavigationId = "navigation-2",
                callbackUrl = "https://example.com/",
                activeWebViewUrl = null,
            ),
        )
    }
}
