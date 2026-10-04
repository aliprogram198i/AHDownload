package com.ahdownload.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLifecyclePolicyTest {
    @Test
    fun cancellationIsLimitedToActiveJobs() {
        assertTrue(DownloadLifecyclePolicy.canCancel(DownloadStatus.DOWNLOADING))
        assertTrue(DownloadLifecyclePolicy.canCancel(DownloadStatus.QUEUED))
        assertFalse(DownloadLifecyclePolicy.canCancel(DownloadStatus.COMPLETED))
    }

    @Test
    fun retryOnlyAppliesToFailedJobs() {
        assertTrue(DownloadLifecyclePolicy.canRetry(DownloadStatus.FAILED))
        assertFalse(DownloadLifecyclePolicy.canRetry(DownloadStatus.DOWNLOADING))
    }

    @Test
    fun ioRetryIsBounded() {
        assertTrue(DownloadLifecyclePolicy.shouldRetryIo(1, 3))
        assertTrue(DownloadLifecyclePolicy.shouldRetryIo(2, 3))
        assertFalse(DownloadLifecyclePolicy.shouldRetryIo(3, 3))
    }
}
