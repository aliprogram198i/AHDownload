package com.ahdownload.domain.download

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** True only while a task is queued or actively executing, not while paused or terminal. */
val DownloadStatus.isActivelyRunning: Boolean
    get() = this == DownloadStatus.QUEUED ||
        this == DownloadStatus.PREPARING ||
        this == DownloadStatus.DOWNLOADING

/** Whether the UI may request that this task be paused. */
val DownloadStatus.canBePaused: Boolean
    get() = isActivelyRunning

/** Whether the UI may request cancellation of this task. */
val DownloadStatus.canBeCancelled: Boolean
    get() = isActivelyRunning || this == DownloadStatus.PAUSED

/** Whether the UI may request that this task be queued again. */
val DownloadStatus.canBeResumed: Boolean
    get() = this == DownloadStatus.PAUSED ||
        this == DownloadStatus.FAILED ||
        this == DownloadStatus.CANCELLED

/** Only terminal tasks may be removed from the history. The media file is kept separately. */
val DownloadStatus.canBeRemovedFromHistory: Boolean
    get() = this == DownloadStatus.COMPLETED ||
        this == DownloadStatus.FAILED ||
        this == DownloadStatus.CANCELLED
