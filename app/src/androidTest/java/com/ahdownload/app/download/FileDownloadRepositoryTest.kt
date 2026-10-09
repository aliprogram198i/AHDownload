package com.ahdownload.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ahdownload.domain.download.DownloadRecordMapper
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Before
import org.junit.Test

class FileDownloadRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteFileStore()
    }

    @Test
    fun persistsRecordsAcrossRepositoryInstances() = runTest {
        val task = task()
        val first = FileDownloadRepository(context)
        first.upsert(DownloadRecordMapper.queued(task, 1000))

        val second = FileDownloadRepository(context)
        val restored = second.get(task.id)

        requireNotNull(restored)
        assertEquals(task, restored.task)
        assertEquals(DownloadStatus.QUEUED, restored.status)
    }

    @Test
    fun exposesLatestHistoryImmediatelyAfterPersist() = runTest {
        val task = task()
        val repository = FileDownloadRepository(context)
        val record = DownloadRecordMapper.queued(task, 1000)

        repository.upsert(record)

        val observed = repository.observeHistory().first()

        assertEquals(1, observed.size)
        assertEquals(record, observed.single())
    }

    @Test
    fun recoveryRequeuesInterruptedDownloadAndResetsProgress() = runTest {
        val task = task()
        val partial = File(task.destinationPath + ".part").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(512))
        }
        val repository = FileDownloadRepository(context)
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

        val persisted = FileDownloadRepository(context).get(task.id)
        requireNotNull(persisted)
        assertEquals(DownloadStatus.QUEUED, persisted.status)
        assertTrue(persisted.updatedAtEpochMs == 2000L)
    }

    private fun task() = DownloadTask(
        id = "instrumented-task",
        sourceUrl = "https://example.com/video.mp4",
        destinationPath = "/data/local/tmp/video.mp4",
    )

    private fun Context.deleteFileStore() {
        File(filesDir, "downloads").deleteRecursively()
    }
}
