package com.ahdownload.app.domain

data class DownloadProgressSnapshot(
    val progress: Int,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?
)

object DownloadProgress {
    fun calculate(
        downloaded: Long,
        total: Long?,
        startedNanos: Long,
        nowNanos: Long,
        initialDownloaded: Long = 0L
    ): DownloadProgressSnapshot {
        val elapsed = ((nowNanos - startedNanos) / 1_000_000_000L).coerceAtLeast(1L)
        val transferred = (downloaded - initialDownloaded).coerceAtLeast(0L)
        val speed = transferred / elapsed
        val progress = if (total != null && total > 0L) {
            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        } else 0
        val eta = if (speed > 0L && total != null) {
            ((total - downloaded).coerceAtLeast(0L) / speed)
        } else null
        return DownloadProgressSnapshot(progress, speed, eta)
    }
}
