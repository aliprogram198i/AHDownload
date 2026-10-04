package com.ahdownload.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.download.DirectDownloadWorker
import java.time.Duration
import java.util.UUID

class DownloadRepository(context: Context) {
    private val app = context.applicationContext
    private val db = DownloadDatabase(app)
    private val lock = Any()

    init { migrateLegacyPreferencesIfNeeded() }

    fun all(): List<DownloadJob> = synchronized(lock) {
        val result = mutableListOf<DownloadJob>()
        db.readableDatabase.query("downloads", null, null, null, null, null, "rowid DESC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toJob()
        }
        result
    }

    fun create(
        url: String,
        title: String = titleFromUrl(url),
        extension: String? = null,
        mergeRequired: Boolean = false,
        audioUrl: String? = null,
        audioExtension: String? = null
    ): DownloadJob {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            sourceUrl = url,
            title = title.ifBlank { "AHDownload file" },
            formatUrl = url,
            status = DownloadStatus.QUEUED,
            progress = 0,
            downloadedBytes = 0L,
            totalBytes = null
        )
        synchronized(lock) { insert(job) }

        val settings = app.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.getBoolean("wifi_only", false)) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            )
            .build()

        val request = OneTimeWorkRequestBuilder<DirectDownloadWorker>()
            .setInputData(workDataOf(
                DirectDownloadWorker.KEY_JOB_ID to job.id,
                DirectDownloadWorker.KEY_URL to url,
                DirectDownloadWorker.KEY_SOURCE_URL to job.sourceUrl,
                DirectDownloadWorker.KEY_TITLE to job.title,
                DirectDownloadWorker.KEY_EXTENSION to extension.orEmpty(),
                DirectDownloadWorker.KEY_MERGE_REQUIRED to mergeRequired,
                DirectDownloadWorker.KEY_AUDIO_URL to audioUrl.orEmpty(),
                DirectDownloadWorker.KEY_AUDIO_EXTENSION to audioExtension.orEmpty()
            ))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag("ahdownload:" + job.id)
            .build()
        WorkManager.getInstance(app).enqueue(request)
        return job
    }

    fun update(id: String, change: (DownloadJob) -> DownloadJob) = synchronized(lock) {
        val current = find(id) ?: return
        val next = change(current)
        val values = android.content.ContentValues().apply {
            put("source_url", next.sourceUrl)
            put("title", next.title)
            put("format_url", next.formatUrl)
            put("status", next.status.name)
            put("progress", next.progress.coerceIn(0, 100))
            put("downloaded_bytes", next.downloadedBytes.coerceAtLeast(0L))
            if (next.totalBytes != null) put("total_bytes", next.totalBytes) else putNull("total_bytes")
            if (next.outputUri != null) put("output_uri", next.outputUri) else putNull("output_uri")
        }
        db.writableDatabase.update("downloads", values, "id=?", arrayOf(id))
    }

    fun cancel(jobId: String) {
        WorkManager.getInstance(app).cancelAllWorkByTag("ahdownload:" + jobId)
        update(jobId) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    private fun find(id: String): DownloadJob? =
        db.readableDatabase.query("downloads", null, "id=?", arrayOf(id), null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.toJob() else null
        }

    private fun insert(job: DownloadJob) {
        val values = android.content.ContentValues().apply {
            put("id", job.id)
            put("source_url", job.sourceUrl)
            put("title", job.title)
            put("format_url", job.formatUrl)
            put("status", job.status.name)
            put("progress", job.progress)
            put("downloaded_bytes", job.downloadedBytes)
            if (job.totalBytes != null) put("total_bytes", job.totalBytes)
            if (job.outputUri != null) put("output_uri", job.outputUri)
        }
        db.writableDatabase.insertOrThrow("downloads", null, values)
    }

    private fun migrateLegacyPreferencesIfNeeded() = synchronized(lock) {
        val count = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM downloads", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        if (count > 0L) return
        val prefs = app.getSharedPreferences("ahdownload_downloads", Context.MODE_PRIVATE)
        val raw = prefs.getString("jobs", null).orEmpty()
        if (raw.isBlank()) return

        runCatching {
            val array = org.json.JSONArray(raw)
            db.beginTransaction()
            try {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    insert(DownloadJob(
                        id = o.getString("id"),
                        sourceUrl = o.getString("sourceUrl"),
                        title = o.getString("title"),
                        formatUrl = o.getString("formatUrl"),
                        status = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.FAILED),
                        progress = o.optInt("progress"),
                        downloadedBytes = o.optLong("downloadedBytes"),
                        totalBytes = o.optLong("totalBytes").takeIf { it > 0L },
                        outputUri = o.optString("outputUri").takeIf { it.isNotBlank() }
                    ))
                }
                db.setTransactionSuccessful()
                prefs.edit().remove("jobs").apply()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun android.database.Cursor.toJob(): DownloadJob {
        fun text(name: String) = getString(getColumnIndexOrThrow(name))
        fun nullableText(name: String): String? {
            val index = getColumnIndexOrThrow(name)
            return if (isNull(index)) null else getString(index).takeIf { it.isNotBlank() }
        }
        val totalIndex = getColumnIndexOrThrow("total_bytes")
        return DownloadJob(
            id = text("id"),
            sourceUrl = text("source_url"),
            title = text("title"),
            formatUrl = text("format_url"),
            status = runCatching { DownloadStatus.valueOf(text("status")) }.getOrDefault(DownloadStatus.FAILED),
            progress = getInt(getColumnIndexOrThrow("progress")),
            downloadedBytes = getLong(getColumnIndexOrThrow("downloaded_bytes")),
            totalBytes = if (isNull(totalIndex)) null else getLong(totalIndex),
            outputUri = nullableText("output_uri")
        )
    }

    private fun titleFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "AHDownload file" }
            .replace(Regex("[\\\\/:*?\\"<>|]"), "_").take(120)
}
