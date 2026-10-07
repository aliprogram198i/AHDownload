package com.ahdownload.domain.download

object DownloadRecordMapper {
    fun queued(task: DownloadTask, nowEpochMs: Long): DownloadRecord =
        DownloadRecord(
            task = task,
            status = DownloadStatus.QUEUED,
            bytesDownloaded = 0,
            totalBytes = null,
            failureCode = null,
            failureDetail = null,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )

    fun fromState(
        current: DownloadRecord,
        state: DownloadState,
        nowEpochMs: Long,
    ): DownloadRecord {
        val mapped = when (state) {
            DownloadState.Queued -> current.copy(
                status = DownloadStatus.QUEUED,
                bytesDownloaded = 0,
                totalBytes = null,
                failureCode = null,
                failureDetail = null,
            )
            DownloadState.Preparing -> current.copy(
                status = DownloadStatus.PREPARING,
                failureCode = null,
                failureDetail = null,
            )
            DownloadState.Paused -> current.copy(
                status = DownloadStatus.PAUSED,
                failureCode = null,
                failureDetail = null,
            )
            is DownloadState.Downloading -> current.copy(
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = state.bytesDownloaded,
                totalBytes = state.totalBytes,
                failureCode = null,
                failureDetail = null,
            )
            DownloadState.Completed -> current.copy(
                status = DownloadStatus.COMPLETED,
                totalBytes = current.totalBytes?.coerceAtLeast(current.bytesDownloaded),
                failureCode = null,
                failureDetail = null,
            )
            is DownloadState.Failed -> current.copy(
                status = DownloadStatus.FAILED,
                failureCode = state.reason.code(),
                failureDetail = state.reason.detail(),
            )
            DownloadState.Cancelled -> current.copy(
                status = DownloadStatus.CANCELLED,
                failureCode = DownloadFailure.Cancelled.code(),
                failureDetail = null,
            )
        }
        return mapped.copy(updatedAtEpochMs = nowEpochMs)
    }

    private fun DownloadFailure.code(): String = when (this) {
        is DownloadFailure.HttpError -> "http_error"
        DownloadFailure.InvalidResponse -> "invalid_response"
        DownloadFailure.StorageError -> "storage_error"
        DownloadFailure.NetworkError -> "network_error"
        DownloadFailure.Cancelled -> "cancelled"
    }

    private fun DownloadFailure.detail(): String? = when (this) {
        is DownloadFailure.HttpError -> code.toString()
        DownloadFailure.InvalidResponse,
        DownloadFailure.StorageError,
        DownloadFailure.NetworkError,
        DownloadFailure.Cancelled -> null
    }
}
