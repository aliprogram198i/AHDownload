package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.isActivelyRunning

/**
 * Status filter for the downloads library. Kept separate from Compose so the
 * mapping can be verified with fast local unit tests.
 */
internal enum class DownloadStatusFilter(val label: String) {
    All("الكل"),
    Active("النشطة"),
    Paused("المتوقفة"),
    Completed("المكتملة"),
    Failed("الفاشلة"),
    Cancelled("الملغاة"),
}

internal fun DownloadStatusFilter.matches(status: DownloadStatus): Boolean = when (this) {
    DownloadStatusFilter.All -> true
    DownloadStatusFilter.Active -> status.isActivelyRunning
    DownloadStatusFilter.Paused -> status == DownloadStatus.PAUSED
    DownloadStatusFilter.Completed -> status == DownloadStatus.COMPLETED
    DownloadStatusFilter.Failed -> status == DownloadStatus.FAILED
    DownloadStatusFilter.Cancelled -> status == DownloadStatus.CANCELLED
}
