package com.ahdownload.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressTest {
    @Test
    fun calculatesProgressSpeedAndEta() {
        val snapshot = DownloadProgress.calculate(
            downloaded = 50L,
            total = 100L,
            startedNanos = 0L,
            nowNanos = 10_000_000_000L
        )
        assertEquals(50, snapshot.progress)
        assertEquals(5L, snapshot.speedBytesPerSecond)
        assertEquals(10L, snapshot.etaSeconds)
    }

    @Test
    fun handlesUnknownTotalWithoutEta() {
        val snapshot = DownloadProgress.calculate(
            downloaded = 100L,
            total = null,
            startedNanos = 0L,
            nowNanos = 5_000_000_000L
        )
        assertEquals(0, snapshot.progress)
        assertEquals(20L, snapshot.speedBytesPerSecond)
        assertNull(snapshot.etaSeconds)
    }
}
