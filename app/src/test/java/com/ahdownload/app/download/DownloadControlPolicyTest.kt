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
}
