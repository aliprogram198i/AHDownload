package com.ahdownload.domain.download

import kotlinx.coroutines.CancellationException

class DownloadCoordinator(
    private val engine: DownloadEngine,
    private val queue: PersistentDownloadQueue,
    private val clock: DownloadExecutionClock = SystemDownloadExecutionClock,
    private val progressPersistIntervalMs: Long = DEFAULT_PROGRESS_PERSIST_INTERVAL_MS,
    private val isPauseRequested: () -> Boolean = { false },
    /**
     * Optional app-aware cancellation intent. When supplied, null means this worker was
     * interrupted/replaced rather than explicitly paused or cancelled by the user.
     * Omitted by existing domain callers to preserve legacy cancellation behavior.
     */
    private val requestedCancellationState: (() -> DownloadState?)? = null,
    /**
     * Disable coordinator-level persistence when an outer worker owns generation-checked
     * cancellation persistence. This avoids a stale cancellation racing a newer resume.
     */
    private val persistCancellationState: Boolean = true,
) {
    init {
        require(progressPersistIntervalMs >= 0) {
            "progressPersistIntervalMs must be >= 0"
        }
    }

    suspend fun execute(
        task: DownloadTask,
        onPersistedState: suspend (DownloadState) -> Unit = {},
    ): DownloadRecord {
        queue.enqueue(task, clock.nowEpochMs())

        var lastPersistedProgressAt = Long.MIN_VALUE
        var latestRecord = queueRecord(task.id)
        var lastObservedProgress: DownloadState.Downloading? = null

        try {
            engine.download(task) { state ->
                val now = clock.nowEpochMs()
                if (state is DownloadState.Downloading) {
                    lastObservedProgress = state
                }
                if (state == DownloadState.Completed) {
                    val finalProgress = lastObservedProgress
                    if (
                        finalProgress != null &&
                        (finalProgress.totalBytes == null ||
                            finalProgress.bytesDownloaded >= finalProgress.totalBytes) &&
                        (latestRecord.bytesDownloaded != finalProgress.bytesDownloaded ||
                            latestRecord.totalBytes != finalProgress.totalBytes)
                    ) {
                        latestRecord = queue.applyState(task.id, finalProgress, now)
                        lastPersistedProgressAt = now
                        onPersistedState(finalProgress)
                    }
                }
                val shouldPersist = when (state) {
                    DownloadState.Queued,
                    DownloadState.Preparing,
                    DownloadState.Paused,
                    DownloadState.Completed,
                    is DownloadState.Failed,
                    DownloadState.Cancelled -> true

                    is DownloadState.Downloading -> {
                        val elapsed = if (lastPersistedProgressAt == Long.MIN_VALUE) {
                            Long.MAX_VALUE
                        } else {
                            (now - lastPersistedProgressAt).coerceAtLeast(0L)
                        }
                        lastPersistedProgressAt == Long.MIN_VALUE ||
                            progressPersistIntervalMs == 0L ||
                            elapsed >= progressPersistIntervalMs
                    }
                }

                if (shouldPersist) {
                    latestRecord = queue.applyState(task.id, state, now)
                    if (state is DownloadState.Downloading) {
                        lastPersistedProgressAt = now
                    }
                    onPersistedState(state)
                }
            }
        } catch (cancelled: CancellationException) {
            val state = if (requestedCancellationState != null) {
                requestedCancellationState.invoke()
            } else if (isPauseRequested()) {
                DownloadState.Paused
            } else {
                DownloadState.Cancelled
            }
            // Do not overwrite persisted task state when WorkManager replaces this worker or
            // stops it for a system lifecycle event. A newer worker/user action owns that state.
            if (state != null && persistCancellationState) {
                latestRecord = queue.applyState(task.id, state, clock.nowEpochMs())
                onPersistedState(state)
            }
            throw cancelled
        }

        return latestRecord
    }

    private suspend fun queueRecord(taskId: String): DownloadRecord =
        queue.get(taskId) ?: error("Download task not found: $taskId")

    private companion object {
        const val DEFAULT_PROGRESS_PERSIST_INTERVAL_MS = 500L
    }
}
