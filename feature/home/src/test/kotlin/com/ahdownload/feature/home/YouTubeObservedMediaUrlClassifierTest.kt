package com.ahdownload.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeObservedMediaUrlClassifierTest {
    @Test
    fun classifiesExplicitVideoMimeAndPreservesEncodedMimeSupport() {
        assertEquals(
            "video",
            classifyYouTubeObservedMediaUrl(
                "https://rr1---sn.googlevideo.com/videoplayback?mime=video%2Fmp4&range=0-512",
            ),
        )
    }

    @Test
    fun classifiesExplicitAudioMime() {
        assertEquals(
            "audio",
            classifyYouTubeObservedMediaUrl(
                "https://rr1---sn.googlevideo.com/videoplayback?type=audio%2Fwebm&range=0-512",
            ),
        )
    }

    @Test
    fun recognizesKnownAudioItagWhenMimeIsAbsent() {
        assertEquals(
            "audio",
            classifyYouTubeObservedMediaUrl(
                "https://rr1---sn.googlevideo.com/videoplayback?itag=251&range=0-512",
            ),
        )
    }

    @Test
    fun recognizesVideoItagWhenMimeIsAbsent() {
        assertEquals(
            "video",
            classifyYouTubeObservedMediaUrl(
                "https://rr1---sn.googlevideo.com/videoplayback?itag=137&range=0-512",
            ),
        )
    }

    @Test
    fun rejectsBareVideoplaybackRequestWithoutMediaIdentity() {
        assertNull(
            classifyYouTubeObservedMediaUrl(
                "https://rr1---sn.googlevideo.com/videoplayback?range=0-512",
            ),
        )
    }

    @Test
    fun rejectsNonGoogleVideoUrls() {
        assertNull(
            classifyYouTubeObservedMediaUrl(
                "https://example.com/videoplayback?itag=137&mime=video%2Fmp4",
            ),
        )
    }
}
