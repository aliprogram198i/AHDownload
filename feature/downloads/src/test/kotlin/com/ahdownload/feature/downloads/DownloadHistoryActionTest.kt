package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHistoryActionTest {
    @Test
    fun removesEligibleHistoryRecordAndReturnsSuccess() = runBlocking {
        val record = record("failed", DownloadStatus.FAILED)
        val repository = InMemoryDownloadRepository(listOf(record))

        assertTrue(deleteDownloadHistoryRecord(repository, record))
        assertNull(repository.get(record.task.id))
    }

    @Test
    fun doesNotRemoveAnActiveDownloadFromHistory() = runBlocking {
        val record = record("active", DownloadStatus.DOWNLOADING)
        val repository = InMemoryDownloadRepository(listOf(record))

        assertFalse(deleteDownloadHistoryRecord(repository, record))
        assertNotNull(repository.get(record.task.id))
    }

    @Test
    fun reportsRepositoryFailureInsteadOfFalseSuccess() = runBlocking {
        val record = record("failed-delete", DownloadStatus.FAILED)
        val repository = InMemoryDownloadRepository(listOf(record), failDeletes = true)

        assertFalse(deleteDownloadHistoryRecord(repository, record))
        assertNotNull(repository.get(record.task.id))
    }

    private fun record(id: String, status: DownloadStatus) = DownloadRecord(
        task = DownloadTask(
            id = id,
            sourceUrl = "https://example.com/$id.mp4",
            destinationPath = "/downloads/$id.mp4",
            displayName = id,
        ),
        status = status,
        bytesDownloaded = 0L,
        totalBytes = null,
        failureCode = null,
        failureDetail = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    private class InMemoryDownloadRepository(
        initialRecords: List<DownloadRecord>,
        private val failDeletes: Boolean = false,
    ) : DownloadRepository {
        private val records = initialRecords.associateBy { it.task.id }.toMutableMap()
        private val history = MutableStateFlow(records.values.toList())

        private fun publish() {
            history.value = records.values.toList()
        }

        override suspend fun upsert(record: DownloadRecord) {
            records[record.task.id] = record
            publish()
        }

        override suspend fun get(taskId: String): DownloadRecord? = records[taskId]

        override suspend fun delete(taskId: String) {
            if (failDeletes) error("simulated repository failure")
            records.remove(taskId)
            publish()
        }

        override suspend fun listHistory(): List<DownloadRecord> = records.values.toList()

        override fun observeHistory(): Flow<List<DownloadRecord>> = history.asStateFlow()

        override suspend fun listActive(): List<DownloadRecord> =
            records.values.filter { it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.PREPARING ||
                it.status == DownloadStatus.DOWNLOADING
            }

        override suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> = emptyList()
    }
}
