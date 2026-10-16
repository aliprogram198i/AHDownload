package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.OkHttpTextClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in live acceptance test. Standard unit-test runs skip the external request;
 * the dedicated GitHub workflow enables it to distinguish parser tests from live service access.
 */
class YouTubeSearchLiveAcceptanceTest {
    @Test
    fun liveSearchReturnsRenderableYouTubeResults() = runTest {
        assumeTrue(
            "Set AH_YOUTUBE_LIVE_ACCEPTANCE=true only in the dedicated live-acceptance workflow.",
            System.getenv("AH_YOUTUBE_LIVE_ACCEPTANCE") == "true",
        )

        val results = YouTubeSearchProvider(OkHttpTextClient()).search(
            query = "music",
            limit = 12,
            diagnosticContext = mapOf("operation_id" to "live-youtube-search-acceptance"),
        )

        assertFalse("Live YouTube search returned no results.", results.isEmpty())
        assertTrue(
            "Results did not include a video or Short renderer.",
            results.any { it.kind == com.ahdownload.domain.search.ContentSearchKind.VIDEO ||
                it.kind == com.ahdownload.domain.search.ContentSearchKind.SHORT },
        )
        assertEquals(
            "Search result IDs should be deduplicated.",
            results.size,
            results.map { it.id }.distinct().size,
        )
        results.forEach { item ->
            assertFalse("Search result title is empty.", item.title.isBlank())
            assertFalse("Search result ID is empty.", item.id.isBlank())
            assertTrue(
                "Unexpected search result URL.",
                item.url.startsWith("https://www.youtube.com/"),
            )
        }
    }
}
