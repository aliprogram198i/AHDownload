package com.ahdownload.domain.download

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistentDownloadQueueTest {
    private val task = DownloadTask(
        id = "task-1",
        sourceUrl = "https://example.com/video.mp4",
        destinationPath = "/downloads/video.mp4",
    )

    @Test
    fun enqueueIsIdempotentForExistingTask() = runTest {
        val repository = FakeDownloadRepository()
        val queue = PersistentDownloadQueue(repository)

        val first = queue.enqueue(task, 1000)
        val second = queue.enqueue(task, 2000)

        assertEquals(first, second)
        assertEquals(1, repository.records.size)
    }


    @Test
    fun enqueueReplacesTaskPayloadWhenSameIdIsRefreshed() = runTest {
        val repository = FakeDownloadRepository()
        val queue = PersistentDownloadQueue(repository)
        queue.enqueue(task, 1000)
        queue.applyState(
            taskId = task.id,
            state = DownloadState.Failed(DownloadFailure.HttpError(403)),
            nowEpochMs = 1100,
        )

        val refreshedTask = task.copy(
            sourceUrl = "https://cdn.example/fresh-video.mp4",
            sourcePageUrl = "https://www.youtube.com/watch?v=abc1234",
        )
        val updated = queue.enqueue(refreshedTask, 1200)

        assertEquals(refreshedTask, updated.task)
        assertEquals(1200, updated.updatedAtEpochMs)
        assertEquals(null, updated.failureCode)
        assertEquals(null, updated.failureDetail)
        assertEquals(refreshedTask, repository.records[task.id]?.task)
    }


    @Test
    fun applyStatePersistsEngineProgress() = runTest {
        val repository = FakeDownloadRepository()
        val queue = PersistentDownloadQueue(repository)
        queue.enqueue(task, 1000)

        val updated = queue.applyState(
            taskId = task.id,
            state = DownloadState.Downloading(512, 2048),
            nowEpochMs = 1200,
        )

        assertEquals(DownloadStatus.DOWNLOADING, updated.status)
        assertEquals(512, updated.bytesDownloaded)
        assertEquals(2048, updated.totalBytes)
        assertEquals(updated, repository.records[task.id])
    }

    @Test
    fun applyStateFailsClearlyForUnknownTask() = runTest {
        val queue = PersistentDownloadQueue(FakeDownloadRepository())

        assertFailsWith<IllegalStateException> {
            queue.applyState(
                taskId = "missing",
                state = DownloadState.Completed,
                nowEpochMs = 1000,
            )
        }
    }

    private class FakeDownloadRepository : DownloadRepository {
        val records = linkedMapOf<String, DownloadRecord>()

        override suspend fun upsert(record: DownloadRecord) {
            records[record.task.id] = record
        }

        override suspend fun get(taskId: String): DownloadRecord? =
            records[taskId]

        override suspend fun listHistory(): List<DownloadRecord> =
            records.values.sortedByDescending { it.updatedAtEpochMs }

        override suspend fun listActive(): List<DownloadRecord> =
            records.values.filter {
                it.status in setOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.PREPARING,
                    DownloadStatus.DOWNLOADING,
                )
            }

        override suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> {
            val recovered = records.values
                .filter {
                    it.status == DownloadStatus.PREPARING ||
                        it.status == DownloadStatus.DOWNLOADING
                }
                .map {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        bytesDownloaded = 0,
                        totalBytes = null,
                        failureCode = null,
                        failureDetail = null,
                        updatedAtEpochMs = nowEpochMs,
                    )
                }

            recovered.forEach { records[it.task.id] = it }
            return recovered
        }
    }
}
