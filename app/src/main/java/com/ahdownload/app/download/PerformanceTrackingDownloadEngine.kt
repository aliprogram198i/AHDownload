package com.ahdownload.app.download

import com.ahdownload.app.performance.PerformanceLogStore
import com.ahdownload.domain.download.DownloadEngine
import com.ahdownload.domain.download.DownloadFailure
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadTask
import kotlinx.coroutines.CancellationException

class PerformanceTrackingDownloadEngine(
    private val delegate: DownloadEngine,
    private val performanceLogStore: PerformanceLogStore,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onState: suspend (DownloadState) -> Unit,
    ) {
        val session = performanceLogStore.beginDownload(task)
        var outcome = "INTERRUPTED"
        try {
            delegate.download(task) { state ->
                performanceLogStore.observeDownload(session, state)
                when (state) {
                    DownloadState.Completed -> outcome = "COMPLETED"
                    is DownloadState.Failed -> outcome = state.toPerformanceOutcome()
                    DownloadState.Cancelled -> outcome = "CANCELLED"
                    DownloadState.Paused -> outcome = "PAUSED"
                    else -> Unit
                }
                onState(state)
            }
        } catch (cancelled: CancellationException) {
            outcome = "CANCELLED"
            throw cancelled
        } catch (error: Throwable) {
            outcome = "FAILED"
            throw error
        } finally {
            performanceLogStore.finishDownload(session, outcome)
        }
    }

    private fun DownloadState.Failed.toPerformanceOutcome(): String = when (val failure = reason) {
        is DownloadFailure.HttpError -> "FAILED_HTTP_${failure.code}"
        DownloadFailure.InvalidResponse -> "FAILED_INVALID_RESPONSE"
        DownloadFailure.StorageError -> "FAILED_STORAGE"
        DownloadFailure.NetworkError -> "FAILED_NETWORK"
        DownloadFailure.Cancelled -> "CANCELLED"
    }
}
