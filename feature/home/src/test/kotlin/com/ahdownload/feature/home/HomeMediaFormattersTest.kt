package com.ahdownload.feature.home

import com.ahdownload.domain.model.MediaKind
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class HomeMediaFormattersTest {
    @Test
    fun normalizesCommonMediaCodecsAndLeavesUnknownValuesReadable() {
        assertEquals("H.264", normalizeCodecForUi("avc1.640028,mp4a.40.2"))
        assertEquals("AV1", normalizeCodecForUi("av01.0.08M.08"))
        assertEquals("VP9", normalizeCodecForUi("vp9.00.51.08"))
        assertEquals("Opus", normalizeCodecForUi("opus"))
        assertEquals("codec-custom", normalizeCodecForUi("codec-custom"))
        assertNull(normalizeCodecForUi(null))
        assertNull(normalizeCodecForUi("  "))
    }

    @Test
    fun formatsDurationWithoutNegativeTimeAndLabelsMediaKinds() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:00", formatDuration(-1L))
        assertEquals("1:05", formatDuration(65_000L))
        assertEquals("61:01", formatDuration(3_661_000L))
        assertEquals("فيديو", kindLabel(MediaKind.Video))
        assertEquals("صوت", kindLabel(MediaKind.Audio))
        assertEquals("صورة", kindLabel(MediaKind.Image))
    }

    @Test
    fun preservesReadablePlatformFallback() {
        assertEquals("YouTube", platformLabel("YouTube"))
        assertEquals("موقع ويب", platformLabel("Web"))
        assertEquals("CustomPlatform", platformLabel("CustomPlatform"))
    }
}
