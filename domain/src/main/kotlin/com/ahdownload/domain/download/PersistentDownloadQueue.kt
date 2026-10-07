package com.ahdownload.domain.download

class PersistentDownloadQueue(
    private val repository: DownloadRepository,
) {
    suspend fun enqueue(task: DownloadTask, nowEpochMs: Long): DownloadRecord {
        val existing = repository.get(task.id)
        if (existing != null) {
            if (existing.task != task) {
                val refreshed = existing.copy(
                    task = task,
                    failureCode = null,
                    failureDetail = null,
                    updatedAtEpochMs = nowEpochMs,
                )
                repository.upsert(refreshed)
                return refreshed
            }
            return existing
        }

        repository.findByContentFingerprint(task.contentFingerprint)?.let { return it }

        val record = DownloadRecordMapper.queued(task, nowEpochMs)
        repository.upsert(record)
        return record
    }

    suspend fun applyState(
        taskId: String,
        state: DownloadState,
        nowEpochMs: Long,
    ): DownloadRecord {
        val current = repository.get(taskId)
            ?: error("Download task not found: $taskId")

        val updated = DownloadRecordMapper.fromState(
            current = current,
            state = state,
            nowEpochMs = nowEpochMs,
        )
        repository.upsert(updated)
        return updated
    }

    suspend fun get(taskId: String): DownloadRecord? =
        repository.get(taskId)

    suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> =
        repository.recoverInterrupted(nowEpochMs)
}
