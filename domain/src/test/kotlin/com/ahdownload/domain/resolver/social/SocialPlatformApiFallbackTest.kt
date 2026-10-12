package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.resolver.HttpTextClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialPlatformApiFallbackTest {
    @Test
    fun vimeoPlayerConfigFindsPublicMp4Media() = runTest {
        var requestedUrl = ""
        val mediaUrl = "https://vod-progressive.akamaized.net/sample/video.mp4?token=sample"
        val client = RecordingTextClient { url ->
            requestedUrl = url
            """{"request":{"files":{"progressive":[{"url":"$mediaUrl","mime":"video/mp4"}]}}}"""
        }

        val result = VimeoSocialPlatformExtractionStrategy().extractAdditionalMedia(
            pageUrl = "https://vimeo.com/76979871",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "vimeo-test",
            logger = DiagnosticLogger { _, _, _, _, _, _ -> },
        )

        assertEquals("https://player.vimeo.com/video/76979871/config", requestedUrl)
        assertEquals(listOf(mediaUrl), result?.mediaUrls)
    }

    @Test
    fun redditPublicJsonFindsVRedditFallbackMp4() = runTest {
        var requestedUrl = ""
        val mediaUrl = "https://v.redd.it/abc123/DASH_720.mp4?source=fallback"
        val client = RecordingTextClient { url ->
            requestedUrl = url
            """[{"data":{"children":[{"data":{"secure_media":{"reddit_video":{"fallback_url":"$mediaUrl"}}}}]}}]"""
        }

        val result = RedditSocialPlatformExtractionStrategy().extractAdditionalMedia(
            pageUrl = "https://www.reddit.com/r/space/comments/lnxpt8/sample/",
            browserMediaCount = 0,
            pageFallbackMediaCount = 0,
            pageClient = client,
            operationId = "reddit-test",
            logger = DiagnosticLogger { _, _, _, _, _, _ -> },
        )

        assertTrue(requestedUrl.startsWith("https://www.reddit.com/comments/lnxpt8.json"))
        assertEquals(listOf(mediaUrl), result?.mediaUrls)
    }

    private class RecordingTextClient(
        private val responseForUrl: (String) -> String,
    ) : HttpTextClient {
        override suspend fun get(url: String): String = responseForUrl(url)
        override suspend fun get(url: String, headers: Map<String, String>): String = responseForUrl(url)
    }
}
