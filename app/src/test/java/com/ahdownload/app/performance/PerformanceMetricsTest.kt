package com.ahdownload.app.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceMetricsTest {
    @Test
    fun computesBytesPerSecondFromByteDeltaAndElapsedMilliseconds() {
        assertEquals(2_048L, PerformanceMetrics.bytesPerSecond(4_096L, 2_000L))
    }

    @Test
    fun returnsZeroForInvalidOrNonPositiveSamples() {
        assertEquals(0L, PerformanceMetrics.bytesPerSecond(-1L, 1_000L))
        assertEquals(0L, PerformanceMetrics.bytesPerSecond(100L, 0L))
        assertEquals(0L, PerformanceMetrics.bytesPerSecond(0L, 1_000L))
    }

    @Test
    fun formatsSpeedsUsingBinaryUnits() {
        assertEquals("1.00 MB/s", PerformanceMetrics.formatSpeed(1_048_576L))
        assertEquals("512 KB/s", PerformanceMetrics.formatSpeed(524_288L))
        assertEquals("—", PerformanceMetrics.formatSpeed(0L))
    }
}
