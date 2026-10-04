package com.ahdownload.app.download

import android.content.Context
import java.io.File

/**
 * Owns per-download scratch space. Every job gets an isolated directory so
 * concurrent downloads and retries can never collide on a shared .part file.
 */
object DownloadTempStore {
    fun jobDir(context: Context, jobId: String): File =
        File(context.applicationContext.cacheDir, "downloads/$jobId").apply { mkdirs() }

    fun clear(context: Context, jobId: String) {
        jobDir(context, jobId).deleteRecursively()
    }

    fun clearAll(context: Context) {
        File(context.applicationContext.cacheDir, "downloads").deleteRecursively()
    }
}
