package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLibraryPresentationTest {
    @Test
    fun filtersVideoAndAudioWithoutChangingRecentOrder() {
        val video = entry("video", "Travel Video.mp4", MediaKind.Video, 1_000L)
        val audio = entry("audio", "Podcast.mp3", MediaKind.Audio, 2_000L)
        val newerVideo = entry("video-new", "Concert.mp4", MediaKind.Video, 3_000L)
        val entries = listOf(video, audio, newerVideo)

        assertEquals(
            listOf("video-new", "video"),
            filterMediaLibraryEntries(entries, MediaLibraryFilter.Video, "")
                .map { it.record.task.id },
        )
        assertEquals(
            listOf("audio"),
            filterMediaLibraryEntries(entries, MediaLibraryFilter.Audio, "")
                .map { it.record.task.id },
        )
    }

    @Test
    fun searchIsCaseInsensitiveAndMatchesDisplayName() {
        val entries = listOf(
            entry("one", "Summer TRIP.mp4", MediaKind.Video, 1_000L),
            entry("two", "Podcast.mp3", MediaKind.Audio, 2_000L),
        )

        assertEquals(
            listOf("one"),
            filterMediaLibraryEntries(entries, MediaLibraryFilter.All, "trip")
                .map { it.record.task.id },
        )
        assertEquals(
            listOf("two"),
            filterMediaLibraryEntries(entries, MediaLibraryFilter.All, "PODCAST")
                .map { it.record.task.id },
        )
    }

    @Test
    fun classifiesLegacyRowsFromUnambiguousFileExtensions() {
        val legacyAudio = record("legacy-audio", "voice-note.wav", null, DownloadProcessingMode.Direct, 1_000L)
        val legacyVideo = record("legacy-video", "clip.mkv", null, DownloadProcessingMode.Direct, 1_000L)
        val unknown = record("unknown", "archive.bin", null, DownloadProcessingMode.Direct, 1_000L)

        assertEquals(MediaLibraryType.Audio, classifyMediaLibraryRecord(legacyAudio))
        assertEquals(MediaLibraryType.Video, classifyMediaLibraryRecord(legacyVideo))
        assertNull(classifyMediaLibraryRecord(unknown))
    }

    @Test
    fun explicitAudioExtractionWinsOverUnknownOrVideoMetadata() {
        val extracted = record("extracted", "result.dat", MediaKind.Video, DownloadProcessingMode.ExtractAudio, 1_000L)
        assertEquals(MediaLibraryType.Audio, classifyMediaLibraryRecord(extracted))
    }

    private fun entry(id: String, name: String, kind: MediaKind, updatedAt: Long) =
        MediaLibraryEntry(
            record = record(id, name, kind, DownloadProcessingMode.Direct, updatedAt),
            type = if (kind == MediaKind.Audio) MediaLibraryType.Audio else MediaLibraryType.Video,
        )

    private fun record(
        id: String,
        name: String,
        kind: MediaKind?,
        processingMode: DownloadProcessingMode,
        updatedAt: Long,
    ) = DownloadRecord(
        task = DownloadTask(
            id = id,
            sourceUrl = "https://example.com/$name",
            destinationPath = "/downloads/$name",
            displayName = name,
            mediaKind = kind,
            processingMode = processingMode,
        ),
        status = DownloadStatus.COMPLETED,
        bytesDownloaded = 10_000L,
        totalBytes = 10_000L,
        failureCode = null,
        failureDetail = null,
        createdAtEpochMs = 500L,
        updatedAtEpochMs = updatedAt,
    )
}
