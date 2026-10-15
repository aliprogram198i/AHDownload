package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadStatus

/**
 * Converts only explicit user controls into persisted states. WorkManager may also cancel a
 * worker because of replacement, constraints, or process lifecycle; those events are not a
 * user cancellation and must not overwrite the task's state.
 */
internal fun downloadStatusForControlIntent(intent: String?): DownloadStatus? = when (intent) {
    "PAUSED" -> DownloadStatus.PAUSED
    "CANCELLED" -> DownloadStatus.CANCELLED
    else -> null
}

internal fun downloadStateForControlIntent(intent: String?): DownloadState? = when (
    downloadStatusForControlIntent(intent)
) {
    DownloadStatus.PAUSED -> DownloadState.Paused
    DownloadStatus.CANCELLED -> DownloadState.Cancelled
    else -> null
}

/** True only for orphaned active records with no explicit user control intent. */
internal fun shouldReconcileDownloadAtStartup(
    status: DownloadStatus,
    controlIntent: String?,
    hasUnfinishedWork: Boolean,
): Boolean {
    if (controlIntent != null || hasUnfinishedWork) return false
    return status == DownloadStatus.QUEUED ||
        status == DownloadStatus.PREPARING ||
        status == DownloadStatus.DOWNLOADING
}
