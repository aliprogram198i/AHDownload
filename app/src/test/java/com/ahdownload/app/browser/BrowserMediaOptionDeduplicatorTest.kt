package com.ahdownload.app.browser

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaOptionDeduplicatorTest {
    @Test
    fun videoOptionsCollapseDuplicateSourcesButKeepDifferentVisibleQualities() {
        val first1080 = candidate(
            id = "video-1",
            url = "https://cdn.example.test/video-a.mp4",
            width = 1920,
            height = 1080,
            fileSizeBytes = 10_000L,
        )
        val duplicate1080 = candidate(
            id = "video-2",
            url = "https://mirror.example.test/video-b.mp4",
            width = 1920,
            height = 1080,
            fileSizeBytes = 10_000L,
        )
        val lower720 = candidate(
            id = "video-3",
            url = "https://cdn.example.test/video-c.mp4",
            width = 1280,
            height = 720,
            fileSizeBytes = 6_000L,
        )
        val videoWithoutEmbeddedAudio = candidate(
            id = "video-4",
            url = "https://cdn.example.test/video-d.mp4",
            width = 1920,
            height = 1080,
            hasAudio = false,
            fileSizeBytes = 10_000L,
        )
        val audioOnly = candidate(
            id = "audio-1",
            url = "https://cdn.example.test/audio.mp3",
            kind = MediaKind.Audio,
            container = MediaContainer.Mp3,
            width = null,
            height = null,
            fps = null,
            bitrateKbps = 128,
            videoCodec = null,
            audioCodec = "mp3",
            hasVideo = false,
            hasAudio = true,
        )

        val result = BrowserMediaOptionDeduplicator.videoOptions(
            listOf(first1080, duplicate1080, lower720, videoWithoutEmbeddedAudio, audioOnly),
        )

        assertEquals(3, result.size)
        assertEquals(listOf(first1080.id, lower720.id, videoWithoutEmbeddedAudio.id), result.map { it.id })
    }

    @Test
    fun audioSourcesCollapseSameVisibleChoiceAndKeepDifferentBitrates() {
        val first128 = candidate(
            id = "audio-1",
            url = "https://cdn.example.test/audio-a.mp3",
            kind = MediaKind.Audio,
            container = MediaContainer.Mp3,
            width = null,
            height = null,
            fps = null,
            bitrateKbps = 128,
            videoCodec = null,
            audioCodec = "mp3",
            hasVideo = false,
            hasAudio = true,
            fileSizeBytes = 1_000L,
        )
        val duplicate128 = candidate(
            id = "audio-2",
            url = "https://mirror.example.test/audio-b.mp3",
            kind = MediaKind.Audio,
            container = MediaContainer.Mp3,
            width = null,
            height = null,
            fps = null,
            bitrateKbps = 128,
            videoCodec = null,
            audioCodec = "MP3",
            hasVideo = false,
            hasAudio = true,
            fileSizeBytes = 1_500L,
        )
        val distinct192 = candidate(
            id = "audio-3",
            url = "https://cdn.example.test/audio-c.mp3",
            kind = MediaKind.Audio,
            container = MediaContainer.Mp3,
            width = null,
            height = null,
            fps = null,
            bitrateKbps = 192,
            videoCodec = null,
            audioCodec = "mp3",
            hasVideo = false,
            hasAudio = true,
        )

        val result = BrowserMediaOptionDeduplicator.audioSources(
            listOf(first128, duplicate128, distinct192),
        )

        assertEquals(2, result.size)
        assertEquals(listOf(first128.id, distinct192.id), result.map { it.id })
        assertTrue(BrowserMediaOptionDeduplicator.audioSourceLabel(result[0]).contains("128 kbps"))
        assertTrue(BrowserMediaOptionDeduplicator.audioSourceLabel(result[1]).contains("192 kbps"))
    }

    private fun candidate(
        id: String,
        url: String,
        kind: MediaKind = MediaKind.Video,
        container: MediaContainer = MediaContainer.Mp4,
        width: Int? = 1920,
        height: Int? = 1080,
        fps: Double? = 30.0,
        bitrateKbps: Int? = 1_500,
        videoCodec: String? = "h264",
        audioCodec: String? = "aac",
        hasVideo: Boolean = true,
        hasAudio: Boolean = true,
        fileSizeBytes: Long? = 10_000L,
    ) = MediaCandidate(
        id = id,
        sourceUrl = url,
        format = MediaFormat(
            id = "format-$id",
            kind = kind,
            container = container,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = bitrateKbps,
            fileSizeBytes = fileSizeBytes,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
        ),
    )
}
