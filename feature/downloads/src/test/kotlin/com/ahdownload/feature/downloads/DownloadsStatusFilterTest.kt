package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsStatusFilterTest {
    @Test
    fun allFilterIncludesEveryDownloadStatus() {
        DownloadStatus.entries.forEach { status ->
            assertTrue(DownloadStatusFilter.All.matches(status))
        }
    }

    @Test
    fun activeFilterIncludesQueuedPreparingAndDownloadingOnly() {
        val activeStatuses = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING,
        )

        DownloadStatus.entries.forEach { status ->
            assertEquals(status in activeStatuses, DownloadStatusFilter.Active.matches(status))
        }
    }

    @Test
    fun eachStatusBelongsToExactlyOneSpecificFilter() {
        val specificFilters = DownloadStatusFilter.entries.filter { it != DownloadStatusFilter.All }

        DownloadStatus.entries.forEach { status ->
            assertEquals(
                "Expected exactly one status filter for $status",
                1,
                specificFilters.count { it.matches(status) },
            )
        }
    }
}
