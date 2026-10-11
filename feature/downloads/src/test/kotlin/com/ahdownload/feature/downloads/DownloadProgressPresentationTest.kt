package com.ahdownload.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressPresentationTest {
    @Test
    fun estimatesRemainingTimeFromKnownTotalAndMeasuredSpeed() {
        val mib = 1024L * 1024L
        assertEquals(
            8L,
            estimatedDownloadRemainingSeconds(
                totalBytes = 50L * mib,
                downloadedBytes = 30L * mib,
                bytesPerSecond = 2_500L * 1024L,
            ),
        )
    }

    @Test
    fun hidesEtaWhenTotalOrPositiveSpeedIsUnknown() {
        assertNull(estimatedDownloadRemainingSeconds(null, 1_000L, 100L))
        assertNull(estimatedDownloadRemainingSeconds(5_000L, 1_000L, null))
        assertNull(estimatedDownloadRemainingSeconds(5_000L, 1_000L, 0L))
        assertNull(estimatedDownloadRemainingSeconds(0L, 1L, 1L))
    }

    @Test
    fun hidesEtaWhenTransferIsCompleteOrEstimateIsTooLong() {
        assertNull(estimatedDownloadRemainingSeconds(1_000L, 1_000L, 100L))
        assertNull(estimatedDownloadRemainingSeconds(1_000L, 1_001L, 100L))
        assertNull(estimatedDownloadRemainingSeconds(100_000_000L, 0L, 1L))
    }

    @Test
    fun neverReturnsZeroForSmallPositiveRemainder() {
        assertEquals(1L, estimatedDownloadRemainingSeconds(2L, 1L, 10L))
    }
}
