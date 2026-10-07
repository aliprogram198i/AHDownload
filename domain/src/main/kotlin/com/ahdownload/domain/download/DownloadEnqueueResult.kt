package com.ahdownload.domain.download

/**
 * User-facing outcome of preparing a download.
 *
 * Keeping this explicit avoids collapsing actionable states such as
 * duplicate content or an unavailable custom directory into a generic failure.
 */
enum class DownloadEnqueueResult {
    QUEUED,
    DUPLICATE,
    INVALID_CUSTOM_LOCATION,
    STORAGE_UNAVAILABLE,
    REJECTED,
}
