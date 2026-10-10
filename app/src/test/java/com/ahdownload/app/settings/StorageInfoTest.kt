package com.ahdownload.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageInfoTest {
    @Test
    fun blocksOnlyWhenAvailableStorageIsBelowHardMinimum() {
        assertEquals(
            DownloadStorageReadiness.INSUFFICIENT_SPACE,
            DownloadStoragePolicy.assess(DownloadStoragePolicy.MINIMUM_FREE_BYTES - 1L),
        )
        assertEquals(
            DownloadStorageReadiness.LOW_SPACE_WARNING,
            DownloadStoragePolicy.assess(DownloadStoragePolicy.MINIMUM_FREE_BYTES),
        )
    }

    @Test
    fun warnsBelowOneGigabyteButDoesNotBlockSmallDownloads() {
        assertEquals(
            DownloadStorageReadiness.LOW_SPACE_WARNING,
            DownloadStoragePolicy.assess(512L * 1024L * 1024L),
        )
        assertEquals(
            DownloadStorageReadiness.READY,
            DownloadStoragePolicy.assess(DownloadStoragePolicy.LOW_SPACE_WARNING_BYTES),
        )
    }

    @Test
    fun treatsNegativeStorageReadingsAsInsufficient() {
        assertEquals(
            DownloadStorageReadiness.INSUFFICIENT_SPACE,
            DownloadStoragePolicy.assess(-1L),
        )
    }
}
