package com.ahdownload.domain.download

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
