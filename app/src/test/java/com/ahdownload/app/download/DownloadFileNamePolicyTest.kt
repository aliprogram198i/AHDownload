package com.ahdownload.app.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNamePolicyTest {
    @Test
    fun truncatesArabicTitleByUtf8BytesRatherThanCharacterCount() {
        val name = DownloadFileNamePolicy.safeBaseName("ع".repeat(120), "fallback")

        assertEquals(DownloadFileNamePolicy.MAX_BASE_NAME_BYTES, name.toByteArray(Charsets.UTF_8).size)
        assertEquals("ع".repeat(50), name)
    }

    @Test
    fun doesNotSplitSupplementaryUnicodeCharacters() {
        val name = DownloadFileNamePolicy.safeBaseName("😀".repeat(80), "fallback")

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= DownloadFileNamePolicy.MAX_BASE_NAME_BYTES)
        assertTrue(name.all { it == '😀'[0] || it == '😀'[1] })
        assertEquals(0, name.length % 2)
    }

    @Test
    fun normalizesLegacyOversizedDestinationDeterministicallyAndKeepsExtension() {
        val original = File("/downloads", "ع".repeat(120) + ".mp4").absolutePath

        val normalized = DownloadFileNamePolicy.normalizeDestinationPath(original, "task-abc123")

        assertNotEquals(original, normalized)
        assertEquals(".mp4", "." + File(normalized).extension)
        assertTrue(File(normalized).name.toByteArray(Charsets.UTF_8).size <= 160)
        assertEquals(
            normalized,
            DownloadFileNamePolicy.normalizeDestinationPath(original, "task-abc123"),
        )
    }

    @Test
    fun preservesPathsWhoseFilenameIsAlreadyWithinSafeLimit() {
        val path = File("/downloads", "ordinary-reel.mp4").absolutePath

        assertEquals(path, DownloadFileNamePolicy.normalizeDestinationPath(path, "task-abc123"))
    }
}
