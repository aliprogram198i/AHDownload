package com.ahdownload.app.settings

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale

data class StorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val freeRatio: Float get() = if (totalBytes > 0L) freeBytes.toFloat() / totalBytes.toFloat() else 0f
    val usedPercent: Int get() = ((1f - freeRatio) * 100f).toInt().coerceIn(0, 100)
    val isLow: Boolean get() = freeBytes < 1L * 1024L * 1024L * 1024L
}

object StorageInfoReader {
    fun read(context: Context): StorageInfo {
        val path = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: context.filesDir.absolutePath
        val stat = StatFs(path)
        return StorageInfo(
            totalBytes = stat.blockCountLong.coerceAtLeast(0L) * stat.blockSizeLong.coerceAtLeast(0L),
            freeBytes = stat.availableBlocksLong.coerceAtLeast(0L) * stat.blockSizeLong.coerceAtLeast(0L),
        )
    }
}

fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index.coerceAtLeast(0)])
}
