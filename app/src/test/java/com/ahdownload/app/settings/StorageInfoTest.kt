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
    fun blocksWhenKnownTransferAndSafetyReserveExceedAvailableSpace() {
        val expectedBytes = 150L * 1024L * 1024L
        val freeBytes = 200L * 1024L * 1024L

        assertEquals(
            DownloadStorageReadiness.INSUFFICIENT_SPACE,
            DownloadStoragePolicy.assess(freeBytes, expectedBytes),
        )
        assertEquals(
            expectedBytes + DownloadStoragePolicy.MINIMUM_FREE_BYTES,
            DownloadStoragePolicy.requiredFreeBytes(expectedBytes),
        )
    }

    @Test
    fun warnsButDoesNotBlockWhenKnownSmallTransferFitsWithReserve() {
        val expectedBytes = 1L * 1024L * 1024L
        val freeBytes = 128L * 1024L * 1024L

        assertEquals(
            DownloadStorageReadiness.LOW_SPACE_WARNING,
            DownloadStoragePolicy.assess(freeBytes, expectedBytes),
        )
    }

    @Test
    fun saturatesRequiredSpaceInsteadOfOverflowingForExtremeSizes() {
        assertEquals(
            Long.MAX_VALUE,
            DownloadStoragePolicy.requiredFreeBytes(Long.MAX_VALUE),
        )
        assertEquals(
            DownloadStorageReadiness.INSUFFICIENT_SPACE,
            DownloadStoragePolicy.assess(Long.MAX_VALUE - 1L, Long.MAX_VALUE),
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
