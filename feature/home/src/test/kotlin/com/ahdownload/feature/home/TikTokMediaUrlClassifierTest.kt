package com.ahdownload.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokMediaUrlClassifierTest {
    @Test
    fun recognizesTikTokCdnVideoUrlWithMimeTypeAndNoFileExtension() {
        val url = "https://v16-webapp.tiktokcdn.com/obj/tos-useast5-p-0068-tx/sample" +
            "?a=1988&mime_type=video_mp4"

        assertTrue(TikTokMediaUrlClassifier.isMediaResource(url))
    }

    @Test
    fun recognizesTikTokVideoTosPathWithoutFileExtensionOrMimeParameter() {
        val url = "https://v16-webapp.tiktokcdn.com/video/tos/useast5/sample"

        assertTrue(TikTokMediaUrlClassifier.isMediaResource(url))
    }

    @Test
    fun recognizesKnownMediaExtensionOnlyOnTrustedTikTokCdnHosts() {
        assertTrue(
            TikTokMediaUrlClassifier.isMediaResource(
                "https://v16.tiktokcdn.com/video/sample.mp4",
            ),
        )
        assertFalse(
            TikTokMediaUrlClassifier.isMediaResource(
                "https://cdn.example.com/video/sample.mp4",
            ),
        )
    }

    @Test
    fun rejectsImageThumbnailsEvenIfTheQueryClaimsVideoMimeType() {
        assertFalse(
            TikTokMediaUrlClassifier.isMediaResource(
                "https://v16-webapp.tiktokcdn.com/obj/tos/sample.jpg?mime_type=video_mp4",
            ),
        )
    }

    @Test
    fun rejectsTikTokPageUrlsAndUnknownCdnResources() {
        assertFalse(
            TikTokMediaUrlClassifier.isMediaResource(
                "https://www.tiktok.com/@creator/video/7345678901234567890",
            ),
        )
        assertFalse(
            TikTokMediaUrlClassifier.isMediaResource(
                "https://v16-webapp.tiktokcdn.com/obj/tos/sample?version=3",
            ),
        )
    }

    @Test
    fun rejectsPlainHttpMediaUrls() {
        assertFalse(
            TikTokMediaUrlClassifier.isMediaResource(
                "http://v16-webapp.tiktokcdn.com/video/sample.mp4",
            ),
        )
    }
}
