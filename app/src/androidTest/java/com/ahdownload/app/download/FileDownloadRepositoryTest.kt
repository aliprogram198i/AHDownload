package com.ahdownload.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ahdownload.domain.download.DownloadRecordMapper
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTemporaryFiles
import com.ahdownload.domain.download.DownloadTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test

class FileDownloadRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        DownloadHistoryDatabase.closeForTests()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DownloadHistoryDatabase.DATABASE_NAME)
        context.getSharedPreferences("ahdownload_download_history_migration", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteFileStore()
        context.deleteTestTemporaryFiles()
    }

    @After
    fun tearDown() {
        DownloadHistoryDatabase.closeForTests()
        context.deleteTestTemporaryFiles()
        context.deleteFileStore()
        context.deleteDatabase(DownloadHistoryDatabase.DATABASE_NAME)
        context.getSharedPreferences("ahdownload_download_history_migration", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun persistsRecordsAcrossRepositoryInstances() = runTest {
        val task = task()
        val first = RoomDownloadRepository(context)
        first.upsert(DownloadRecordMapper.queued(task, 1000))

        val second = RoomDownloadRepository(context)
        val restored = second.get(task.id)

        requireNotNull(restored)
        assertEquals(task, restored.task)
        assertEquals(DownloadStatus.QUEUED, restored.status)
    }

    @Test
    fun exposesLatestHistoryImmediatelyAfterPersist() = runTest {
        val task = task()
        val repository = RoomDownloadRepository(context)
        val record = DownloadRecordMapper.queued(task, 1000)

        repository.upsert(record)

        val observed = repository.observeHistory().first()

        assertEquals(1, observed.size)
        assertEquals(record, observed.single())
    }

    @Test
    fun importsLegacyAtomicJsonHistoryIntoRoomWithoutLosingTaskFields() = runTest {
        val record = DownloadRecordMapper.queued(task("legacy-record"), 1000)
        val legacyFile = File(context.filesDir, "downloads/downloads.json")
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText(DownloadRecordJsonCodec().encode(listOf(record)))

        val repository = RoomDownloadRepository(context)
        assertEquals(record, repository.get(record.task.id))
        assertFalse(legacyFile.exists())

        DownloadHistoryDatabase.closeForTests()
        val restarted = RoomDownloadRepository(context)
        assertEquals(record, restarted.get(record.task.id))
    }

    @Test
    fun repositoryInstancesShareLiveHistoryUpdates() = runTest {
        val observer = RoomDownloadRepository(context)
        val writer = RoomDownloadRepository(context)
        val record = DownloadRecordMapper.queued(task(), 1000)

        writer.upsert(record)

        assertEquals(listOf(record), observer.observeHistory().first())
        assertEquals(record, observer.get(record.task.id))
    }

    @Test
    fun recoveryRequeuesInterruptedDownloadAndResetsProgress() = runTest {
        val task = task()
        val partial = File(task.destinationPath + ".part").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(512))
        }
        val repository = RoomDownloadRepository(context)
        val queued = DownloadRecordMapper.queued(task, 1000)
        repository.upsert(queued)
        repository.upsert(
            DownloadRecordMapper.fromState(
                queued,
                DownloadState.Downloading(512, 2048),
                1100,
            ),
        )

        val recovered = repository.recoverInterrupted(2000)

        assertEquals(1, recovered.size)
        assertEquals(DownloadStatus.QUEUED, recovered.single().status)
        assertEquals(512L, recovered.single().bytesDownloaded)
        assertEquals(2048L, recovered.single().totalBytes)
        assertTrue(partial.exists())

        val persisted = RoomDownloadRepository(context).get(task.id)
        requireNotNull(persisted)
        assertEquals(DownloadStatus.QUEUED, persisted.status)
        assertTrue(persisted.updatedAtEpochMs == 2000L)
    }

    @Test
    fun recoveryNeverResumesIntentionallyPausedOrCancelledTasks() = runTest {
        val repository = RoomDownloadRepository(context)
        val pausedTask = task("paused-task")
        val cancelledTask = task("cancelled-task")
        val queuedPaused = DownloadRecordMapper.queued(pausedTask, 1000)
        val queuedCancelled = DownloadRecordMapper.queued(cancelledTask, 1000)

        repository.upsert(queuedPaused.copy(status = DownloadStatus.PAUSED, updatedAtEpochMs = 1100))
        repository.upsert(queuedCancelled.copy(status = DownloadStatus.CANCELLED, updatedAtEpochMs = 1200))

        assertTrue(repository.recoverInterrupted(2000).isEmpty())
        assertEquals(DownloadStatus.PAUSED, repository.get(pausedTask.id)?.status)
        assertEquals(DownloadStatus.CANCELLED, repository.get(cancelledTask.id)?.status)
    }

    @Test
    fun recoveryUsesTheCurrentTaskScopedPartialNotAnotherTasksBytes() = runTest {
        val task = task()
        val ownPartial = DownloadTemporaryFiles.temporaryFile(task.destinationPath, task.id).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(123))
        }
        val unrelatedPartial = DownloadTemporaryFiles.temporaryFile(task.destinationPath, "unrelated-task").apply {
            writeBytes(ByteArray(999))
        }
        val repository = RoomDownloadRepository(context)
        val queued = DownloadRecordMapper.queued(task, 1000)
        repository.upsert(queued)
        repository.upsert(
            DownloadRecordMapper.fromState(
                queued,
                DownloadState.Downloading(123, 2048),
                1100,
            ),
        )

        val recovered = repository.recoverInterrupted(2000)

        assertEquals(123L, recovered.single().bytesDownloaded)
        assertTrue(ownPartial.exists())
        assertTrue(unrelatedPartial.exists())
    }

    private fun task(id: String = "instrumented-task") = DownloadTask(
        id = id,
        sourceUrl = "https://example.com/video.mp4",
        destinationPath = File(context.cacheDir, "video.mp4").absolutePath,
    )

    private fun Context.deleteTestTemporaryFiles() {
        cacheDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.name.startsWith("video.mp4.") && file.name.endsWith(".part")
            }
            .forEach { it.delete() }
    }

    private fun Context.deleteFileStore() {
        File(filesDir, "downloads").deleteRecursively()
    }
}
