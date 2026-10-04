package com.ahdownload.app.ui

import android.os.StatFs
import android.os.storage.StorageManager
import android.content.Context

data class StorageInfo(
    val totalBytes: Long,
    val availableBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedPercent: Int get() = if (totalBytes > 0L) ((usedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
}

object StorageIntelligence {
    fun read(context: Context): StorageInfo {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(context.filesDir)
        return runCatching {
            val stats = storageManager.getAllocatableBytes(uuid)
            val total = StorageManager.getStorageBytesForUuid(context, uuid)
            StorageInfo(totalBytes = total, availableBytes = stats)
        }.getOrElse {
            val stat = StatFs(context.filesDir.path)
            StorageInfo(
                totalBytes = stat.totalBytes,
                availableBytes = stat.availableBytes
            )
        }
    }

    fun format(bytes: Long): String {
        if (bytes < 1024L) return bytes.toString() + " B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return "%.1f %s".format(java.util.Locale.US, value, units[index])
    }
}
