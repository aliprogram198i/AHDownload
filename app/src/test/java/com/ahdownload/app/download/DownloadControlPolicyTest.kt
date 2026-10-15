package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadControlPolicyTest {
    @Test
    fun mapsOnlyExplicitPauseAndCancelIntentsToStatuses() {
        assertEquals(DownloadStatus.PAUSED, downloadStatusForControlIntent("PAUSED"))
        assertEquals(DownloadStatus.CANCELLED, downloadStatusForControlIntent("CANCELLED"))
        assertEquals(null, downloadStatusForControlIntent(null))
        assertEquals(null, downloadStatusForControlIntent("ACTIVE"))
        assertEquals(null, downloadStatusForControlIntent("REPLACED"))
    }

    @Test
    fun mapsOnlyExplicitPauseAndCancelIntentsToWorkerStates() {
        assertEquals(DownloadState.Paused, downloadStateForControlIntent("PAUSED"))
        assertEquals(DownloadState.Cancelled, downloadStateForControlIntent("CANCELLED"))
        assertEquals(null, downloadStateForControlIntent(null))
        assertEquals(null, downloadStateForControlIntent("ACTIVE"))
        assertEquals(null, downloadStateForControlIntent("REPLACED"))
    }

    @Test
    fun startupRecoveryOnlyRestoresOrphanedActiveRecords() {
        val recoverable = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING,
        )
        DownloadStatus.entries.forEach { status ->
            assertEquals(
                "status=$status",
                status in recoverable,
                shouldReconcileDownloadAtStartup(status, null, hasUnfinishedWork = false),
            )
            assertEquals(
                "unfinished work must be preserved for $status",
                false,
                shouldReconcileDownloadAtStartup(status, null, hasUnfinishedWork = true),
            )
        }
    }

    @Test
    fun startupRecoveryNeverOverridesAnExplicitControlIntent() {
        listOf("PAUSED", "CANCELLED", "ACTIVE", "REPLACED").forEach { intent ->
            assertEquals(
                false,
                shouldReconcileDownloadAtStartup(
                    DownloadStatus.DOWNLOADING, intent, hasUnfinishedWork = false,
                ),
            )
        }
    }
}
