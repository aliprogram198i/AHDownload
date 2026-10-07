package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartResultEngineTest {
    @Test
    fun groupsDeduplicatesAndPicksBestOverall() {
        val candidates = listOf(
            candidate("v720", MediaKind.Video, 720, 2500, 10000000),
            candidate("v720-duplicate", MediaKind.Video, 720, 2500, 10000000),
            candidate("v1080", MediaKind.Video, 1080, 4500, 20000000),
            candidate("a320", MediaKind.Audio, null, 320, 5000000),
        )

        val result = SmartResultEngine().build(candidates)

        assertEquals(3, result.all.size)
        assertEquals("v1080", result.bestOverall?.candidate?.id)
        assertEquals(MediaResultGroup.Video, result.video.first().group)
        assertEquals(MediaResultGroup.Audio, result.audio.single().group)
        assertTrue(result.bestQuality != null)
    }

    @Test
    fun normalizesCommonCodecsAndQualityLabels() {
        val candidate = MediaCandidate(
            id = "h264",
            sourceUrl = "https://cdn.example/h264",
            format = MediaFormat(
                id = "h264",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                videoCodec = "avc1.640028",
                audioCodec = "mp4a.40.2",
                height = 1080,
                fps = 59.94,
                bitrateKbps = 4500,
                hasVideo = true,
                hasAudio = true,
            ),
        )

        val model = SmartResultEngine().build(listOf(candidate)).all.single()

        assertEquals("1080p", model.qualityLabel)
        assertEquals("H.264 · AAC", model.codecLabel)
        assertEquals("60 FPS", model.fpsLabel)
    }

    @Test
    fun recommendsVideoBeforeAudioWhenBothExist() {
        val candidates = listOf(
            candidate("audio", MediaKind.Audio, null, 320, 5000000),
            candidate("video", MediaKind.Video, 1080, 6000, 20000000),
        )

        val result = SmartResultEngine().build(candidates)

        assertEquals("video", result.bestOverall?.candidate?.id)
        assertEquals("video", result.visible.first().candidate.id)
    }

    @Test
    fun exposesOnlyMuxedVideoInVisibleVideoResults() {
        val videoOnly = MediaCandidate(
            id = "video-only",
            sourceUrl = "https://cdn.example/video-only",
            format = MediaFormat(
                id = "video-only",
                kind = MediaKind.Video,
                container = MediaContainer.Webm,
                videoCodec = "vp9",
                height = 1080,
                bitrateKbps = 5000,
                hasVideo = true,
                hasAudio = false,
            ),
        )
        val muxed = MediaCandidate(
            id = "muxed",
            sourceUrl = "https://cdn.example/muxed",
            format = MediaFormat(
                id = "muxed",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                videoCodec = "avc1.640028",
                audioCodec = "mp4a.40.2",
                height = 720,
                bitrateKbps = 3000,
                hasVideo = true,
                hasAudio = true,
            ),
        )

        val result = SmartResultEngine().build(listOf(videoOnly, muxed))

        assertEquals(listOf("muxed"), result.visible.map { it.candidate.id })
    }

    @Test
    fun prefersCompatibleVideoEncodingWithinSameQuality() {
        val webm = MediaCandidate(
            id = "webm-vp9",
            sourceUrl = "https://cdn.example/webm",
            format = MediaFormat(
                id = "webm-vp9",
                kind = MediaKind.Video,
                container = MediaContainer.Webm,
                videoCodec = "vp9",
                audioCodec = "opus",
                height = 1080,
                fps = 30.0,
                bitrateKbps = 5000,
                hasVideo = true,
                hasAudio = true,
            ),
        )
        val mp4 = MediaCandidate(
            id = "mp4-h264",
            sourceUrl = "https://cdn.example/mp4",
            format = MediaFormat(
                id = "mp4-h264",
                kind = MediaKind.Video,
                container = MediaContainer.Mp4,
                videoCodec = "avc1.640028",
                audioCodec = "mp4a.40.2",
                height = 1080,
                fps = 30.0,
                bitrateKbps = 4500,
                hasVideo = true,
                hasAudio = true,
            ),
        )

        val result = SmartResultEngine().build(listOf(webm, mp4))

        assertEquals(listOf("mp4-h264", "webm-vp9"), result.video.map { it.candidate.id })
    }

    @Test
    fun keepsDifferentFrameRatesAsDistinctOptions() {
        val fps30 = candidate("v1080-30", MediaKind.Video, 1080, 4500, 20000000).copy(
            format = candidate("v1080-30", MediaKind.Video, 1080, 4500, 20000000).format.copy(fps = 30.0),
        )
        val fps60 = candidate("v1080-60", MediaKind.Video, 1080, 4500, 20000000).copy(
            format = candidate("v1080-60", MediaKind.Video, 1080, 4500, 20000000).format.copy(fps = 60.0),
        )

        val result = SmartResultEngine().build(listOf(fps30, fps60))

        assertEquals(2, result.video.size)
        assertEquals(listOf("v1080-60", "v1080-30"), result.video.map { it.candidate.id })
    }

    private fun candidate(
        id: String,
        kind: MediaKind,
        height: Int?,
        bitrateKbps: Int,
        size: Long,
    ) = MediaCandidate(
        id = id,
        sourceUrl = "https://cdn.example/$id",
        format = MediaFormat(
            id = id,
            kind = kind,
            container = if (kind == MediaKind.Video) MediaContainer.Mp4 else MediaContainer.M4a,
            height = height,
            bitrateKbps = bitrateKbps,
            fileSizeBytes = size,
            hasVideo = kind == MediaKind.Video,
            hasAudio = true,
        ),
    )
}
