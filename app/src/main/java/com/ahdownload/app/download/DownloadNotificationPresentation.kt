package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadState

/**
 * Pure presentation helpers for WorkManager progress notifications.
 *
 * Keep these formatters independent from worker lifecycle/storage logic to make the
 * localized status contract easy to exercise with JVM unit tests.
 */
internal fun DownloadState.toNotificationText(
    lowStorageWarningForTask: Boolean,
    notificationBytesPerSecond: Long,
): String = when (this) {
    DownloadState.Queued -> "في قائمة الانتظار"
    DownloadState.Preparing -> if (lowStorageWarningForTask) {
        "المساحة منخفضة — راقب التنزيل"
    } else {
        "جاري تجهيز التنزيل"
    }
    is DownloadState.Downloading -> {
        totalBytes?.let { total ->
            val percent = if (total > 0) {
                (bytesDownloaded * 100 / total).coerceIn(0L, 100L)
            } else {
                0L
            }
            buildString {
                append("جاري التنزيل — ")
                append(percent)
                append("%")
                if (lowStorageWarningForTask) append(" · مساحة منخفضة")
                if (notificationBytesPerSecond > 0L) {
                    append(" · ")
                    append(formatNotificationBytes(notificationBytesPerSecond))
                    append("/s")
                    if (total > bytesDownloaded) {
                        val etaSeconds = (total - bytesDownloaded) / notificationBytesPerSecond
                        append(" · ")
                        append(formatNotificationEta(etaSeconds))
                    }
                }
            }
        } ?: "جاري التنزيل"
    }
    DownloadState.Paused -> "تم الإيقاف المؤقت"
    DownloadState.Completed -> "اكتمل التنزيل"
    is DownloadState.Failed -> "فشل التنزيل"
    DownloadState.Cancelled -> "تم إلغاء التنزيل"
}

internal fun DownloadState.totalBytesOrNull(): Long? = when (this) {
    is DownloadState.Downloading -> totalBytes
    else -> null
}

internal fun DownloadState.bytesDownloadedOrNull(): Long? = when (this) {
    is DownloadState.Downloading -> bytesDownloaded
    else -> null
}

private fun formatNotificationBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> "${bytes / (1024L * 1024L * 1024L)} GB"
}

private fun formatNotificationEta(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val minutes = safe / 60L
    val secs = safe % 60L
    return if (minutes > 0L) "${minutes}د ${secs.toString()}ث" else "${secs}ث"
}
