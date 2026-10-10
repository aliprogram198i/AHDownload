package com.ahdownload.feature.downloads

data class DownloadPerformanceSummary(
    val durationMs: Long,
    val transferredBytes: Long,
    val averageBytesPerSecond: Long,
    val peakBytesPerSecond: Long,
)
