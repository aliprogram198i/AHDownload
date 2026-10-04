package com.ahdownload.app.download

import android.content.Context
import java.io.File

/**
 * Persistent app-specific scratch space. A unique directory per job prevents
 * collisions while surviving process death and WorkManager retry windows.
 */
object DownloadTempStore {
    fun jobDir(context: Context, jobId: String): File {
        val root = context.applicationContext.getExternalFilesDir("downloads")
            ?: File(context.applicationContext.filesDir, "downloads")
        return File(root, ".tmp/$jobId").apply { mkdirs() }
    }

    fun clear(context: Context, jobId: String) {
        jobDir(context, jobId).deleteRecursively()
    }

    fun clearStale(context: Context, activeJobIds: Set<String>): Int {
        val root = File(
            context.applicationContext.getExternalFilesDir("downloads")
                ?: File(context.applicationContext.filesDir, "downloads"),
            ".tmp"
        )
        var removed = 0
        root.listFiles().orEmpty().forEach { dir ->
            if (dir.isDirectory && dir.name !in activeJobIds && dir.deleteRecursively()) removed++
        }
        if (root.isDirectory && root.listFiles().isNullOrEmpty()) root.delete()
        return removed
    }
}
