package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.isActivelyRunning
import java.util.Locale

/** Stable, user-selectable ordering for the downloads library. Active work remains visible first. */
internal enum class DownloadSortOption(val label: String) {
    Recent("الأحدث"),
    Oldest("الأقدم"),
    Name("الاسم"),
    Largest("الحجم الأكبر"),
}

internal fun sortDownloadRecords(
    records: List<DownloadRecord>,
    option: DownloadSortOption,
): List<DownloadRecord> {
    val activeFirst = compareByDescending<DownloadRecord> { it.status.isActivelyRunning }
    return when (option) {
        DownloadSortOption.Recent -> records.sortedWith(
            activeFirst.thenByDescending { it.updatedAtEpochMs },
        )
        DownloadSortOption.Oldest -> records.sortedWith(
            activeFirst.thenBy { it.createdAtEpochMs }.thenBy { it.updatedAtEpochMs },
        )
        DownloadSortOption.Name -> records.sortedWith(
            activeFirst
                .thenBy { it.task.displayName.orEmpty().trim().lowercase(Locale.ROOT) }
                .thenByDescending { it.updatedAtEpochMs },
        )
        DownloadSortOption.Largest -> records.sortedWith(
            activeFirst
                .thenByDescending { it.sortSizeBytes() }
                .thenByDescending { it.updatedAtEpochMs },
        )
    }
}

private fun DownloadRecord.sortSizeBytes(): Long =
    (totalBytes?.takeIf { it > 0L } ?: bytesDownloaded).coerceAtLeast(0L)
