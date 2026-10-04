package com.ahdownload.domain.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class UrlNormalizerTest {
    private val normalizer = UrlNormalizer()

    @Test
    fun addsHttpsWhenSchemeIsMissing() {
        assertEquals(
            "https://example.com/video",
            normalizer.normalize("example.com/video").getOrThrow(),
        )
    }

    @Test
    fun normalizesHostCase() {
        assertEquals(
            "https://example.com/path",
            normalizer.normalize("HTTPS://EXAMPLE.COM/path").getOrThrow(),
        )
    }

    @Test
    fun rejectsUnsupportedScheme() {
        val error = assertFails {
            normalizer.normalize("ftp://example.com").getOrThrow()
        }
        assertTrue(error is IllegalArgumentException)
    }
}
