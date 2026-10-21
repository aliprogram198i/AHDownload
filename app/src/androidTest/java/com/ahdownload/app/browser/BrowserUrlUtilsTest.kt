package com.ahdownload.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.domain.validation.ValidationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserUrlUtilsTest {
    @Test
    fun normalizesWebAddressesAndSearchTermsWithoutAllowingCredentials() {
        assertEquals("https://example.com/path", normalizeBrowserUrl("example.com/path"))
        assertEquals("http://example.com/article", normalizeBrowserUrl("http://example.com/article"))
        assertTrue(normalizeBrowserUrl("search term")!!.startsWith("https://www.google.com/search?q="))
        assertNull(normalizeBrowserUrl("https://user:secret@example.com/path"))
        assertNull(normalizeBrowserUrl("file:///storage/emulated/0/private.txt"))
    }

    @Test
    fun usesSafeFallbackForMissingOrInvalidHosts() {
        assertNull(normalizeBrowserUrl("  "))
        assertNull(normalizeBrowserUrl("https:///missing-host"))
        assertEquals("current", viewUrlOrCurrent("", "current"))
        assertEquals("example.com", safeHost("https://example.com/video.mp4"))
        assertEquals("unknown", safeHost("not a URL"))
    }

    @Test
    fun mapsValidationFailuresToUserFacingArabicMessages() {
        assertTrue(ValidationFailure.HtmlResponse.toBrowserMessage().contains("صفحة ويب"))
        assertTrue(ValidationFailure.InvalidUrl.toBrowserMessage().contains("غير صالح"))
    }
}
