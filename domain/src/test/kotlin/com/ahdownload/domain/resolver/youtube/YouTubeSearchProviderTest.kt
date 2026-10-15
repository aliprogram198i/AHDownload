package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.search.ContentSearchKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchProviderTest {
    @Test
    fun parsesVideoResultsFromInitialData() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{"videoRenderer":{"videoId":"abc123","title":{"runs":[{"text":"Test video"}]},"lengthText":{"simpleText":"4:20"},"ownerText":{"runs":[{"text":"Test channel"}]},"thumbnail":{"thumbnails":[{"url":"https://img.example/small.jpg"},{"url":"https://img.example/large.jpg"}]}}}};</script></html>""",
        )

        val results = YouTubeSearchProvider(client).search("test", limit = 5)

        assertEquals(1, results.size)
        assertEquals("abc123", results.first().id)
        assertEquals("Test video", results.first().title)
        assertEquals("4:20", results.first().durationLabel)
        assertEquals("Test channel", results.first().channelLabel)
        assertEquals(ContentSearchKind.VIDEO, results.first().kind)
        assertTrue(results.first().thumbnailUrl?.contains("large.jpg") == true)
    }

    @Test
    fun parsesShortsAndChannelResultsAlongsideVideos() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{
              "videoRenderer":{"videoId":"video123","title":{"simpleText":"Regular video"}},
              "reelItemRenderer":{"videoId":"short123","headline":{"simpleText":"Short clip"},"thumbnail":{"thumbnails":[{"url":"//img.example/short.jpg"}]}},
              "channelRenderer":{"channelId":"UCchannel123","title":{"simpleText":"Creator channel"},"subscriberCountText":{"simpleText":"1.2M subscribers"},"thumbnail":{"thumbnails":[{"url":"https://img.example/channel.jpg"}]}}
            }};</script></html>""",
        )

        val results = YouTubeSearchProvider(client).search("creator", limit = 12)

        assertEquals(3, results.size)
        assertEquals(
            setOf(ContentSearchKind.VIDEO, ContentSearchKind.SHORT, ContentSearchKind.CHANNEL),
            results.map { it.kind }.toSet(),
        )
        val short = results.single { it.kind == ContentSearchKind.SHORT }
        assertEquals("https://www.youtube.com/shorts/short123", short.url)
        assertEquals("https://img.example/short.jpg", short.thumbnailUrl)
        val channel = results.single { it.kind == ContentSearchKind.CHANNEL }
        assertEquals("https://www.youtube.com/channel/UCchannel123", channel.url)
        assertEquals("1.2M subscribers", channel.subtitle)
    }

    @Test
    fun preservesSourceOrderAndPromotesDuplicateVideoToShorts() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{
              "videoRenderer":{"videoId":"video-first","title":{"simpleText":"First video"}},
              "channelRenderer":{"channelId":"UCcreator","title":{"simpleText":"Creator"}},
              "videoRenderer":{"videoId":"shared-short","title":{"simpleText":"Short before promotion"}},
              "reelItemRenderer":{"videoId":"shared-short","headline":{"simpleText":"Actual Short"}},
              "videoRenderer":{"videoId":"video-last","title":{"simpleText":"Last video"}}
            }};</script></html>""",
        )

        val results = YouTubeSearchProvider(client).search("ordered", limit = 12)

        assertEquals(
            listOf("video-first", "UCcreator", "shared-short", "video-last"),
            results.map { it.id },
        )
        assertEquals(ContentSearchKind.SHORT, results[2].kind)
        assertEquals("Actual Short", results[2].title)
    }

    @Test
    fun parsesModernShortsLockupRenderer() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{"shortsLockupViewModel":{"entityId":"shorts-shelf-item-short789","overlayMetadata":{"primaryText":{"content":"Modern short"}},"thumbnail":{"sources":[{"url":"https://img.example/modern.jpg"}]}}}};</script></html>""",
        )

        val result = YouTubeSearchProvider(client).search("shorts").single()

        assertEquals("short789", result.id)
        assertEquals(ContentSearchKind.SHORT, result.kind)
        assertEquals("https://img.example/modern.jpg", result.thumbnailUrl)
    }

    private fun textClient(html: String): HttpTextClient = object : HttpTextClient {
        override suspend fun get(url: String): String = html
    }
}
