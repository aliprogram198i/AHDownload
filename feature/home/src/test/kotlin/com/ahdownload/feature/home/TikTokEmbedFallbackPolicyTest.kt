package com.ahdownload.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokEmbedFallbackPolicyTest {
    @Test
    fun createsPublicEmbedOnlyForCanonicalHttpsVideoPages() {
        assertEquals(
            "https://www.tiktok.com/embed/v2/7697553870800588050",
            TikTokEmbedFallbackPolicy.publicEmbedUrl(
                "https://www.tiktok.com/@creator/video/7697553870800588050?lang=en",
            ),
        )
        assertEquals(
            "https://www.tiktok.com/embed/v2/7345678901234567890",
            TikTokEmbedFallbackPolicy.publicEmbedUrl(
                "https://m.tiktok.com/@creator/video/7345678901234567890",
            ),
        )
    }

    @Test
    fun rejectsNonCanonicalOrUntrustedPageUrls() {
        assertEquals(
            null,
            TikTokEmbedFallbackPolicy.publicEmbedUrl("https://vm.tiktok.com/ZMshortCode/"),
        )
        assertEquals(
            null,
            TikTokEmbedFallbackPolicy.publicEmbedUrl(
                "https://tiktok.com.evil.example/@creator/video/7345678901234567890",
            ),
        )
        assertEquals(
            null,
            TikTokEmbedFallbackPolicy.publicEmbedUrl(
                "http://www.tiktok.com/@creator/video/7345678901234567890",
            ),
        )
        assertEquals(
            null,
            TikTokEmbedFallbackPolicy.publicEmbedUrl(
                "https://www.tiktok.com/@creator/video/not-a-video-id",
            ),
        )
    }

    @Test
    fun identifiesOnlyTikTokPublicEmbedPageUrls() {
        assertTrue(
            TikTokEmbedFallbackPolicy.isPublicEmbedUrl(
                "https://www.tiktok.com/embed/v2/7345678901234567890",
            ),
        )
        assertFalse(
            TikTokEmbedFallbackPolicy.isPublicEmbedUrl(
                "https://www.tiktok.com/@creator/video/7345678901234567890",
            ),
        )
        assertFalse(
            TikTokEmbedFallbackPolicy.isPublicEmbedUrl(
                "https://www.tiktok.com.evil.example/embed/v2/7345678901234567890",
            ),
        )
    }
}
