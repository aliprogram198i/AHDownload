package com.ahdownload.feature.downloads

/**
 * Returns a conservative ETA only when the total size and a positive measured speed are known.
 * Estimates longer than one day are omitted because transfer rates can change significantly.
 */
internal fun estimatedDownloadRemainingSeconds(
    totalBytes: Long?,
    downloadedBytes: Long,
    bytesPerSecond: Long?,
): Long? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    val speed = bytesPerSecond?.takeIf { it > 0L } ?: return null
    if (downloadedBytes < 0L) return null

    val remaining = total - downloadedBytes
    if (remaining <= 0L) return null

    val seconds = (remaining.toDouble() / speed.toDouble())
        .toLong()
        .coerceAtLeast(1L)
    return seconds.takeIf { it <= MAX_REPORTED_REMAINING_SECONDS }
}

private const val MAX_REPORTED_REMAINING_SECONDS = 24L * 60L * 60L
