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
