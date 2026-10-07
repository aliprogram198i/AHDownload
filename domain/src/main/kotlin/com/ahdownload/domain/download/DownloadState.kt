package com.ahdownload.domain.download

sealed interface DownloadState {
    data object Queued : DownloadState
    data object Preparing : DownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : DownloadState
    data object Paused : DownloadState
    data object Completed : DownloadState
    data class Failed(val reason: DownloadFailure) : DownloadState
    data object Cancelled : DownloadState
}

sealed interface DownloadFailure {
    data class HttpError(val code: Int) : DownloadFailure
    data object InvalidResponse : DownloadFailure
    data object StorageError : DownloadFailure
    data object NetworkError : DownloadFailure
    data object Cancelled : DownloadFailure
}
