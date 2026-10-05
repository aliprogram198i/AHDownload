package com.ahdownload.domain.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadCoordinatorTest {
    @Test
    fun executePersistsTerminalCompletion() = runTest {
        val repository = FakeRepository()
        val queue = PersistentDownloadQueue(repository)
        val coordinator = DownloadCoordinator(
            engine = FakeEngine(
                DownloadState.Queued,
                DownloadState.Preparing,
                DownloadState.Downloading(0, 100),
                DownloadState.Downloading(100, 100),
                DownloadState.Completed,
            ),
            queue = queue,
            clock = FixedClock(),
            progressPersistIntervalMs = 500,
        )

        val result = coordinator.execute(task())

        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertEquals(100, result.bytesDownloaded)
        assertEquals(100, result.totalBytes)
        assertEquals(DownloadStatus.COMPLETED, repository.records[task().id]?.status)
    }

    @Test
    fun cancellationIsPersistedAndRethrown() = runTest {
        val repository = FakeRepository()
        val queue = PersistentDownloadQueue(repository)
        val coordinator = DownloadCoordinator(
            engine = object : DownloadEngine {
                override suspend fun download(
                    task: DownloadTask,
                    onState: suspend (DownloadState) -> Unit,
                ) {
                    onState(DownloadState.Preparing)
                    throw CancellationException("test cancellation")
                }
            },
            queue = queue,
            clock = FixedClock(),
        )

        assertFailsWith<CancellationException> {
            coordinator.execute(task())
        }
        assertEquals(
            DownloadStatus.CANCELLED,
            repository.records[task().id]?.status,
        )
    }

    @Test
    fun progressCheckpointDoesNotWriteEveryChunk() = runTest {
        val repository = FakeRepository()
        val queue = PersistentDownloadQueue(repository)
        val clock = StepClock(stepMs = 100)
        val coordinator = DownloadCoordinator(
            engine = FakeEngine(
                DownloadState.Queued,
                DownloadState.Preparing,
                DownloadState.Downloading(0, 1000),
                DownloadState.Downloading(100, 1000),
                DownloadState.Downloading(200, 1000),
                DownloadState.Downloading(300, 1000),
                DownloadState.Completed,
            ),
            queue = queue,
            clock = clock,
            progressPersistIntervalMs = 500,
        )

        coordinator.execute(task())

        assertEquals(5, repository.upsertCount)
    }

    private fun task() = DownloadTask(
        id = "coordinator-task",
        sourceUrl = "https://example.com/video.mp4",
        destinationPath = "/downloads/video.mp4",
    )

    private class FixedClock : DownloadExecutionClock {
        override fun nowEpochMs(): Long = 1000
    }

    private class StepClock(private val stepMs: Long) : DownloadExecutionClock {
        private var current = 0L
        override fun nowEpochMs(): Long {
            val value = current
            current += stepMs
            return value
        }
    }

    private class FakeEngine(
        private vararg val states: DownloadState,
    ) : DownloadEngine {
        override suspend fun download(
            task: DownloadTask,
            onState: suspend (DownloadState) -> Unit,
        ) {
            states.forEach(onState)
        }
    }

    private class FakeRepository : DownloadRepository {
        val records = linkedMapOf<String, DownloadRecord>()
        var upsertCount = 0

        override suspend fun upsert(record: DownloadRecord) {
            records[record.task.id] = record
            upsertCount++
        }

        override suspend fun get(taskId: String): DownloadRecord? = records[taskId]

        override suspend fun listHistory(): List<DownloadRecord> =
            records.values.toList()

        override suspend fun listActive(): List<DownloadRecord> =
            records.values.filter {
                it.status in setOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.PREPARING,
                    DownloadStatus.DOWNLOADING,
                )
            }

        override suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> =
            emptyList()
    }
}
