package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.HttpTextClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchProviderTest {
    @Test
    fun parsesVideoResultsFromInitialData() = runTest {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String =
                """<html><script>var ytInitialData = {"contents":{"videoRenderer":{"videoId":"abc123","title":{"runs":[{"text":"Test video"}]},"lengthText":{"simpleText":"4:20"},"ownerText":{"runs":[{"text":"Test channel"}]},"thumbnail":{"thumbnails":[{"url":"https://img.example/small.jpg"},{"url":"https://img.example/large.jpg"}]}}};</script></html>"""
        }

        val results = YouTubeSearchProvider(client).search("test", limit = 5)

        assertEquals(1, results.size)
        assertEquals("abc123", results.first().id)
        assertEquals("Test video", results.first().title)
        assertEquals("4:20", results.first().durationLabel)
        assertEquals("Test channel", results.first().channelLabel)
        assertTrue(results.first().thumbnailUrl?.contains("large.jpg") == true)
    }
}
