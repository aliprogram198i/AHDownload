package com.ahdownload.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.download.DirectDownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val maxHistory = 100
    private val db = DownloadDatabase(app)
    private val lock = Any()
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs

    init {
        migrateLegacyPreferencesIfNeeded()
        refresh()
    }

    fun all(): List<DownloadJob> = synchronized(lock) {
        queryAll()
    }

    fun create(
        url: String,
        title: String = titleFromUrl(url),
        extension: String? = null,
        mergeRequired: Boolean = false,
        audioUrl: String? = null,
        audioExtension: String? = null,
        thumbnailUrl: String? = null,
        durationMs: Long? = null
    ): DownloadJob {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            sourceUrl = url,
            title = title.ifBlank { "AHDownload file" },
            formatUrl = url,
            status = DownloadStatus.QUEUED,
            progress = 0,
            downloadedBytes = 0L,
            totalBytes = null,
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs,
            extension = extension,
            mergeRequired = mergeRequired,
            audioUrl = audioUrl,
            audioExtension = audioExtension
        )
        synchronized(lock) { insert(job) }
        trimHistory()

        val settings = app.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.getBoolean("wifi_only", false)) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            ).build()
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
        WorkManager.getInstance(app).enqueueUniqueWork(
            workName(job.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
        return job
    }

    fun update(id: String, change: (DownloadJob) -> DownloadJob) {
        synchronized(lock) {
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
                put("speed_bps", next.speedBytesPerSec.coerceAtLeast(0L))
                if (next.etaSeconds != null) put("eta_seconds", next.etaSeconds) else putNull("eta_seconds")
                if (next.errorCode != null) put("error_code", next.errorCode) else putNull("error_code")
                if (next.extension != null) put("extension", next.extension) else putNull("extension")
                put("merge_required", if (next.mergeRequired) 1 else 0)
                if (next.audioUrl != null) put("audio_url", next.audioUrl) else putNull("audio_url")
                if (next.audioExtension != null) put("audio_extension", next.audioExtension) else putNull("audio_extension")
                if (next.thumbnailUrl != null) put("thumbnail_url", next.thumbnailUrl) else putNull("thumbnail_url")
                if (next.durationMs != null) put("duration_ms", next.durationMs) else putNull("duration_ms")
            }
            db.writableDatabase.update("downloads", values, "id=?", arrayOf(id))
        }
        refresh()
    }

    fun cancel(jobId: String) {
        WorkManager.getInstance(app).cancelAllWorkByTag("ahdownload:" + jobId)
        update(jobId) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    fun delete(jobId: String) {
        WorkManager.getInstance(app).cancelAllWorkByTag("ahdownload:" + jobId)
        synchronized(lock) { db.writableDatabase.delete("downloads", "id=?", arrayOf(jobId)) }
        refresh()
    }

    private fun refresh() { _jobs.value = all() }

    fun retry(jobId: String): Boolean {
        val job = synchronized(lock) { find(jobId) } ?: return false
        if (job.status != DownloadStatus.FAILED) return false
        val settings = app.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.getBoolean("wifi_only", false)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DirectDownloadWorker>()
            .setInputData(workDataOf(
                DirectDownloadWorker.KEY_JOB_ID to job.id,
                DirectDownloadWorker.KEY_URL to job.formatUrl,
                DirectDownloadWorker.KEY_SOURCE_URL to job.sourceUrl,
                DirectDownloadWorker.KEY_TITLE to job.title,
                DirectDownloadWorker.KEY_EXTENSION to job.extension.orEmpty(),
                DirectDownloadWorker.KEY_MERGE_REQUIRED to job.mergeRequired,
                DirectDownloadWorker.KEY_AUDIO_URL to job.audioUrl.orEmpty(),
                DirectDownloadWorker.KEY_AUDIO_EXTENSION to job.audioExtension.orEmpty()
            ))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag("ahdownload:" + job.id)
            .build()
        update(job.id) { it.copy(status = DownloadStatus.QUEUED, progress = 0, downloadedBytes = 0L, totalBytes = null, outputUri = null, errorCode = null, speedBytesPerSec = 0L, etaSeconds = null) }
        WorkManager.getInstance(app).enqueueUniqueWork(
            workName(job.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
        return true
    }

    /** Enqueue one refreshed format as the single authoritative worker for this job. */
    fun requeueWithRefreshedFormat(jobId: String, format: ResolvedFormat): Boolean {
        val job = synchronized(lock) { find(jobId) } ?: return false
        if (format.url.isBlank()) return false
        val settings = app.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.getBoolean("wifi_only", false)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DirectDownloadWorker>()
            .setInputData(workDataOf(
                DirectDownloadWorker.KEY_JOB_ID to job.id,
                DirectDownloadWorker.KEY_URL to format.url,
                DirectDownloadWorker.KEY_SOURCE_URL to job.sourceUrl,
                DirectDownloadWorker.KEY_TITLE to job.title,
                DirectDownloadWorker.KEY_EXTENSION to format.ext,
                DirectDownloadWorker.KEY_MERGE_REQUIRED to format.mergeRequired,
                DirectDownloadWorker.KEY_AUDIO_URL to format.audioUrl.orEmpty(),
                DirectDownloadWorker.KEY_AUDIO_EXTENSION to format.audioExt.orEmpty()
            ))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag("ahdownload:" + job.id)
            .build()
        update(job.id) {
            it.copy(
                formatUrl = format.url,
                extension = format.ext,
                mergeRequired = format.mergeRequired,
                audioUrl = format.audioUrl,
                audioExtension = format.audioExt,
                status = DownloadStatus.QUEUED,
                progress = 0,
                downloadedBytes = 0L,
                totalBytes = null,
                outputUri = null,
                errorCode = null,
                speedBytesPerSec = 0L,
                etaSeconds = null
            )
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
            workName(job.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
        return true
    }

    private fun workName(jobId: String): String = "ahdownload-job:$jobId"

    private fun trimHistory() {
        synchronized(lock) {
            db.writableDatabase.delete(
                "downloads",
                "id IN (SELECT id FROM downloads WHERE status IN ('COMPLETED','FAILED','CANCELLED') ORDER BY rowid DESC LIMIT -1 OFFSET ?)",
                arrayOf(maxHistory.toString())
            )
        }
        refresh()
    }

    private fun queryAll(): List<DownloadJob> {
        val result = mutableListOf<DownloadJob>()
        db.readableDatabase.query("downloads", null, null, null, null, null, "rowid DESC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toJob()
        }
        return result
    }

    private fun find(id: String): DownloadJob? =
        db.readableDatabase.query("downloads", null, "id=?", arrayOf(id), null, null, null, "1").use {
            if (it.moveToFirst()) it.toJob() else null
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
            put("speed_bps", job.speedBytesPerSec.coerceAtLeast(0L))
            if (job.etaSeconds != null) put("eta_seconds", job.etaSeconds)
            if (job.errorCode != null) put("error_code", job.errorCode)
            if (job.extension != null) put("extension", job.extension)
            put("merge_required", if (job.mergeRequired) 1 else 0)
            if (job.audioUrl != null) put("audio_url", job.audioUrl)
            if (job.audioExtension != null) put("audio_extension", job.audioExtension)
            if (job.thumbnailUrl != null) put("thumbnail_url", job.thumbnailUrl)
            if (job.durationMs != null) put("duration_ms", job.durationMs)
        }
        db.writableDatabase.insertOrThrow("downloads", null, values)
    }

    private fun migrateLegacyPreferencesIfNeeded() {
        synchronized(lock) {
            val count = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM downloads", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            if (count == 0L) {
                val prefs = app.getSharedPreferences("ahdownload_downloads", Context.MODE_PRIVATE)
                val raw = prefs.getString("jobs", null).orEmpty()
                if (raw.isNotBlank()) runCatching {
                    val array = org.json.JSONArray(raw)
                    db.writableDatabase.beginTransaction()
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
                        db.writableDatabase.setTransactionSuccessful()
                        prefs.edit().remove("jobs").apply()
                    } finally { db.writableDatabase.endTransaction() }
                }
            }
        }
    }

    private fun android.database.Cursor.toJob(): DownloadJob {
        fun text(name: String) = getString(getColumnIndexOrThrow(name))
        fun nullableText(name: String): String? {
            val index = getColumnIndexOrThrow(name)
            return if (isNull(index)) null else getString(index).takeIf { it.isNotBlank() }
        }
        return DownloadJob(
            id = text("id"),
            sourceUrl = text("source_url"),
            title = text("title"),
            formatUrl = text("format_url"),
            status = runCatching { DownloadStatus.valueOf(text("status")) }.getOrDefault(DownloadStatus.FAILED),
            progress = getInt(getColumnIndexOrThrow("progress")),
            downloadedBytes = getLong(getColumnIndexOrThrow("downloaded_bytes")),
            totalBytes = getLong(getColumnIndexOrThrow("total_bytes")).takeIf { !isNull(getColumnIndexOrThrow("total_bytes")) },
            outputUri = nullableText("output_uri"),
            thumbnailUrl = nullableText("thumbnail_url"),
            durationMs = getLong(getColumnIndexOrThrow("duration_ms")).takeIf { !isNull(getColumnIndexOrThrow("duration_ms")) },
            speedBytesPerSec = getLong(getColumnIndexOrThrow("speed_bps")),
            etaSeconds = getLong(getColumnIndexOrThrow("eta_seconds")).takeIf { !isNull(getColumnIndexOrThrow("eta_seconds")) },
            errorCode = nullableText("error_code"),
            extension = nullableText("extension"),
            mergeRequired = getInt(getColumnIndexOrThrow("merge_required")) != 0,
            audioUrl = nullableText("audio_url"),
            audioExtension = nullableText("audio_extension")
        )
    }

    private fun titleFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "AHDownload file" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)

    companion object {
        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadRepository(context.applicationContext).also { instance = it }
            }
    }
}
