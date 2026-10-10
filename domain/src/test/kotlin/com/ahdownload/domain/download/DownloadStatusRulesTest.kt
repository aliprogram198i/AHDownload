package com.ahdownload.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadStatusRulesTest {
    @Test
    fun onlyQueuedPreparingAndDownloadingAreActivelyRunning() {
        assertEquals(
            setOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING),
            DownloadStatus.values().filter { it.isActivelyRunning }.toSet(),
        )
    }

    @Test
    fun pausedTasksCanBeCancelledResumedButNotRemovedFromHistory() {
        val status = DownloadStatus.PAUSED

        assertEquals(false, status.isActivelyRunning)
        assertEquals(false, status.canBePaused)
        assertEquals(true, status.canBeCancelled)
        assertEquals(true, status.canBeResumed)
        assertEquals(false, status.canBeRemovedFromHistory)
    }

    @Test
    fun onlyTerminalStatusesCanBeRemovedFromHistory() {
        assertEquals(
            setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED),
            DownloadStatus.values().filter { it.canBeRemovedFromHistory }.toSet(),
        )
    }
}
