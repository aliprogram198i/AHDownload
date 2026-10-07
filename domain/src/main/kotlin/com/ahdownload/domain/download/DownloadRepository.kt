package com.ahdownload.domain.download

import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    suspend fun upsert(record: DownloadRecord)
    suspend fun get(taskId: String): DownloadRecord?
    suspend fun delete(taskId: String)
    suspend fun listHistory(): List<DownloadRecord>
    fun observeHistory(): Flow<List<DownloadRecord>>
    suspend fun listActive(): List<DownloadRecord>

    suspend fun findByContentFingerprint(fingerprint: String?): DownloadRecord? =
        if (fingerprint.isNullOrBlank()) null else listHistory().firstOrNull {
            it.task.contentFingerprint == fingerprint
        }

    suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord>
}
