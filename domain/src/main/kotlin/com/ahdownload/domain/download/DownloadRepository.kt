package com.ahdownload.domain.download

interface DownloadRepository {
    suspend fun upsert(record: DownloadRecord)
    suspend fun get(taskId: String): DownloadRecord?
    suspend fun listHistory(): List<DownloadRecord>
    suspend fun listActive(): List<DownloadRecord>
    suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord>
}
