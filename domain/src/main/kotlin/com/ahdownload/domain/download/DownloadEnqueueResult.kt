package com.ahdownload.domain.download

enum class DownloadEnqueueResult {
    QUEUED,
    DUPLICATE,
    INVALID_DESTINATION,
    STORAGE_UNAVAILABLE,
}
