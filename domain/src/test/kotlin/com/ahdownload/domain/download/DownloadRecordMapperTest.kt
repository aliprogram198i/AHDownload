package com.ahdownload.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadRecordMapperTest {
    private val task = DownloadTask(
        id = "task-1",
        sourceUrl = "https://example.com/video.mp4",
        destinationPath = "/downloads/video.mp4",
    )

    @Test
    fun queuedCreatesCleanInitialRecord() {
        val record = DownloadRecordMapper.queued(task, 1000)

        assertEquals(DownloadStatus.QUEUED, record.status)
        assertEquals(0, record.bytesDownloaded)
        assertNull(record.totalBytes)
        assertNull(record.failureCode)
        assertEquals(1000, record.createdAtEpochMs)
    }

    @Test
    fun downloadingPersistsRealProgress() {
        val initial = DownloadRecordMapper.queued(task, 1000)

        val updated = DownloadRecordMapper.fromState(
            initial,
            DownloadState.Downloading(bytesDownloaded = 512, totalBytes = 2048),
            1200,
        )

        assertEquals(DownloadStatus.DOWNLOADING, updated.status)
        assertEquals(512, updated.bytesDownloaded)
        assertEquals(2048, updated.totalBytes)
        assertEquals(1200, updated.updatedAtEpochMs)
    }

    @Test
    fun failurePersistsStableFailureCode() {
        val initial = DownloadRecordMapper.queued(task, 1000)

        val updated = DownloadRecordMapper.fromState(
            initial,
            DownloadState.Failed(DownloadFailure.HttpError(403)),
            1300,
        )

        assertEquals(DownloadStatus.FAILED, updated.status)
        assertEquals("http_error", updated.failureCode)
        assertEquals("403", updated.failureDetail)
    }

    @Test
    fun terminalStateClearsPreviousFailure() {
        val failed = DownloadRecord(
            task = task,
            status = DownloadStatus.FAILED,
            bytesDownloaded = 0,
            totalBytes = null,
            failureCode = "network_error",
            failureDetail = null,
            createdAtEpochMs = 1000,
            updatedAtEpochMs = 1100,
        )

        val completed = DownloadRecordMapper.fromState(
            failed,
            DownloadState.Completed,
            1200,
        )

        assertEquals(DownloadStatus.COMPLETED, completed.status)
        assertNull(completed.failureCode)
        assertNull(completed.failureDetail)
    }
}
