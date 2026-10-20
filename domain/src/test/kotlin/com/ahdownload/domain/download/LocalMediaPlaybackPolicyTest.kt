package com.ahdownload.domain.download

import com.ahdownload.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaPlaybackPolicyTest {

    @Test
    fun extractedAudioOverridesOriginalVideoKind() {
        assertEquals(
            LocalMediaPlaybackType.AUDIO,
            resolveLocalMediaPlaybackType(
                mediaKind = MediaKind.Video,
                processingMode = DownloadProcessingMode.ExtractAudio,
                displayName = "concert.mp3",
                destinationPath = "/tmp/concert.mp3",
            ),
        )
    }

    @Test
    fun resolvesPersistedAudioAndVideoKinds() {
        assertEquals(
            LocalMediaPlaybackType.AUDIO,
            resolveLocalMediaPlaybackType(
                MediaKind.Audio, DownloadProcessingMode.Direct, "track", "/downloads/track",
            ),
        )
        assertEquals(
            LocalMediaPlaybackType.VIDEO,
            resolveLocalMediaPlaybackType(
                MediaKind.Video, DownloadProcessingMode.Direct, "clip", "/downloads/clip",
            ),
        )
    }

    @Test
    fun infersOnlySupportedLocalFormatsWhenKindIsUnknown() {
        assertEquals(
            LocalMediaPlaybackType.VIDEO,
            resolveLocalMediaPlaybackType(
                MediaKind.Unknown, DownloadProcessingMode.Direct, "clip.mp4", "/tmp/clip",
            ),
        )
        assertEquals(
            LocalMediaPlaybackType.AUDIO,
            resolveLocalMediaPlaybackType(
                MediaKind.Unknown, DownloadProcessingMode.Direct, "track", "/tmp/track.flac",
            ),
        )
        assertEquals(
            null,
            resolveLocalMediaPlaybackType(
                MediaKind.Unknown, DownloadProcessingMode.Direct, "scan.pdf", "/tmp/scan.pdf",
            ),
        )
        assertEquals(
            null,
            resolveLocalMediaPlaybackType(
                MediaKind.Image, DownloadProcessingMode.Direct, "preview.mp4", "/tmp/preview.mp4",
            ),
        )
        assertEquals(
            null,
            resolveLocalMediaPlaybackType(
                MediaKind.Video, DownloadProcessingMode.Direct, "old-video.avi", "/tmp/old-video.avi",
            ),
        )
        assertEquals(
            LocalMediaPlaybackType.VIDEO,
            resolveLocalMediaPlaybackType(
                MediaKind.Video, DownloadProcessingMode.Direct, "movie.mp4", "/tmp/movie.bin",
            ),
        )
    }

    @Test
    fun internalPlayerRequiresACompletedAvailableLocalMediaFile() {
        val type = LocalMediaPlaybackType.VIDEO
        assertTrue(
            shouldOfferInternalPlayback(
                DownloadStatus.COMPLETED, true, "content://media/external/video/42", type,
            ),
        )
        assertFalse(
            shouldOfferInternalPlayback(
                DownloadStatus.DOWNLOADING, true, "content://media/external/video/42", type,
            ),
        )
        assertFalse(
            shouldOfferInternalPlayback(
                DownloadStatus.COMPLETED, false, "content://media/external/video/42", type,
            ),
        )
        assertFalse(
            shouldOfferInternalPlayback(
                DownloadStatus.COMPLETED, true, "https://example.com/video.mp4", type,
            ),
        )
        assertFalse(
            shouldOfferInternalPlayback(
                DownloadStatus.COMPLETED, true, "content://media/external/video/42", null,
            ),
        )
    }

    @Test
    fun youtubeWatchAndShortsUrlsAreAcceptedOnlyOnOfficialHttpsHosts() {
        assertTrue(isTrustedYouTubePlaybackUrl("https://www.youtube.com/watch?v=abc123"))
        assertTrue(isTrustedYouTubePlaybackUrl("https://m.youtube.com/shorts/abc123"))
        assertTrue(isTrustedYouTubePlaybackUrl("https://youtu.be/abc123"))
        assertFalse(isTrustedYouTubePlaybackUrl("http://www.youtube.com/watch?v=abc123"))
        assertFalse(isTrustedYouTubePlaybackUrl("https://youtube.com.evil.example/watch?v=abc123"))
        assertFalse(isTrustedYouTubePlaybackUrl("https://evil.example/?next=https://youtube.com/watch?v=abc123"))
        assertFalse(isTrustedYouTubePlaybackUrl("https://www.youtube.com/channel/UC123"))
        assertFalse(isTrustedYouTubePlaybackUrl("https://www.youtube.com@evil.example/watch?v=abc123"))
        assertFalse(isTrustedYouTubePlaybackUrl("https://www.youtube.com/watch"))
    }
}
