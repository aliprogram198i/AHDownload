package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.model.MediaKind
import java.util.Locale

internal enum class MediaLibraryType(val label: String) {
    Video("فيديو"),
    Audio("صوت"),
}

internal enum class MediaLibraryFilter(val label: String) {
    All("الكل"),
    Video("الفيديو"),
    Audio("الصوت"),
}

internal data class MediaLibraryEntry(
    val record: DownloadRecord,
    val type: MediaLibraryType,
)

/**
 * Classifies only known audio/video records. The extension fallback supports older history rows
 * that predate persisted media-kind metadata, while ambiguous or unknown formats are omitted.
 */
internal fun classifyMediaLibraryRecord(record: DownloadRecord): MediaLibraryType? {
    if (record.task.processingMode == DownloadProcessingMode.ExtractAudio ||
        record.task.mediaKind == MediaKind.Audio
    ) {
        return MediaLibraryType.Audio
    }
    if (record.task.mediaKind == MediaKind.Video) return MediaLibraryType.Video

    val extensions = sequenceOf(record.task.destinationPath, record.task.displayName)
        .filterNotNull()
        .map {
            it.substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
        }
        .toList()

    if (extensions.any { it in AUDIO_EXTENSIONS }) return MediaLibraryType.Audio
    if (extensions.any { it in VIDEO_EXTENSIONS }) return MediaLibraryType.Video
    return null
}

internal fun filterMediaLibraryEntries(
    entries: List<MediaLibraryEntry>,
    filter: MediaLibraryFilter,
    query: String,
): List<MediaLibraryEntry> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return entries.asSequence()
        .filter { entry ->
            when (filter) {
                MediaLibraryFilter.All -> true
                MediaLibraryFilter.Video -> entry.type == MediaLibraryType.Video
                MediaLibraryFilter.Audio -> entry.type == MediaLibraryType.Audio
            }
        }
        .filter { entry ->
            normalizedQuery.isEmpty() ||
                entry.record.task.displayName.orEmpty().lowercase(Locale.ROOT).contains(normalizedQuery)
        }
        .sortedWith(
            compareByDescending<MediaLibraryEntry> { it.record.updatedAtEpochMs }
                .thenBy { it.record.task.id },
        )
        .toList()
}

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "3gp", "mpg", "mpeg", "ts", "webm")
