package com.ahdownload.domain.download

import kotlinx.coroutines.CancellationException

class DownloadCoordinator(
    private val engine: DownloadEngine,
    private val queue: PersistentDownloadQueue,
    private val clock: DownloadExecutionClock = SystemDownloadExecutionClock,
    private val progressPersistIntervalMs: Long = DEFAULT_PROGRESS_PERSIST_INTERVAL_MS,
) {
    init {
        require(progressPersistIntervalMs >= 0) {
            "progressPersistIntervalMs must be >= 0"
        }
    }

    suspend fun execute(task: DownloadTask): DownloadRecord {
        queue.enqueue(task, clock.nowEpochMs())

        var lastPersistedProgressAt = Long.MIN_VALUE
        var latestRecord = queueRecord(task.id)

        try {
            engine.download(task) { state ->
                val now = clock.nowEpochMs()
                val shouldPersist = when (state) {
                    DownloadState.Queued,
                    DownloadState.Preparing,
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
                }
            }
        } catch (cancelled: CancellationException) {
            latestRecord = queue.applyState(
                task.id,
                DownloadState.Cancelled,
                clock.nowEpochMs(),
            )
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
