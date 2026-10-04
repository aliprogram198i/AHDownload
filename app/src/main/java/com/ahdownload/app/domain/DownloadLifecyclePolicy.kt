package com.ahdownload.app.domain

object DownloadLifecyclePolicy {
    fun canCancel(status: DownloadStatus): Boolean =
        status == DownloadStatus.QUEUED ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.RETRYING

    fun canRetry(status: DownloadStatus): Boolean =
        status == DownloadStatus.FAILED

    fun isTerminal(status: DownloadStatus): Boolean =
        status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED

    fun shouldRetryIo(attempt: Int, maxAttempts: Int): Boolean =
        attempt >= 1 && maxAttempts > 1 && attempt < maxAttempts
}
