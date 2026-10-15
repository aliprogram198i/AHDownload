package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadLibraryPresentationTest {
    @Test
    fun recentSortKeepsActiveWorkFirstThenUsesNewestUpdate() {
        val olderCompleted = record("old", "Old", DownloadStatus.COMPLETED, 500, 1_000, 1_100)
        val newestCompleted = record("new", "New", DownloadStatus.COMPLETED, 700, 1_100, 1_900)
        val active = record("active", "Active", DownloadStatus.DOWNLOADING, 10, 1_800, 1_850)

        assertEquals(
            listOf("active", "new", "old"),
            sortDownloadRecords(listOf(olderCompleted, newestCompleted, active), DownloadSortOption.Recent)
                .map { it.task.id },
        )
    }

    @Test
    fun nameAndSizeSortsAreDeterministic() {
        val zeta = record("z", "Zeta", DownloadStatus.COMPLETED, 4_000, 1_000, 1_500)
        val alphaSmall = record("a", "Alpha", DownloadStatus.COMPLETED, 100, 1_000, 1_300)
        val alphaLarge = record("b", "alpha", DownloadStatus.COMPLETED, 8_000, 1_000, 1_400)

        assertEquals(
            listOf("a", "b", "z"),
            sortDownloadRecords(listOf(zeta, alphaLarge, alphaSmall), DownloadSortOption.Name)
                .map { it.task.id },
        )
        assertEquals(
            listOf("b", "z", "a"),
            sortDownloadRecords(listOf(zeta, alphaLarge, alphaSmall), DownloadSortOption.Largest)
                .map { it.task.id },
        )
    }

    @Test
    fun oldestSortUsesCreationTimeAfterKeepingActiveWorkFirst() {
        val newer = record("newer", "Newer", DownloadStatus.COMPLETED, 10, 2_000, 2_100)
        val older = record("older", "Older", DownloadStatus.COMPLETED, 10, 1_000, 2_000)
        val active = record("active", "Active", DownloadStatus.QUEUED, 0, 3_000, 3_000)

        assertEquals(
            listOf("active", "older", "newer"),
            sortDownloadRecords(listOf(newer, older, active), DownloadSortOption.Oldest)
                .map { it.task.id },
        )
    }

    private fun record(
        id: String,
        name: String,
        status: DownloadStatus,
        bytes: Long,
        createdAt: Long,
        updatedAt: Long,
    ) = DownloadRecord(
        task = DownloadTask(
            id = id,
            sourceUrl = "https://example.com/$id.mp4",
            destinationPath = "/downloads/$name.mp4",
            displayName = name,
        ),
        status = status,
        bytesDownloaded = bytes,
        totalBytes = bytes,
        failureCode = null,
        failureDetail = null,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
    )
}
