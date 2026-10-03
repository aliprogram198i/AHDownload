package com.ahdownload.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.download.DirectDownloadWorker
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DownloadRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("ahdownload_downloads", Context.MODE_PRIVATE)

    fun all(): List<DownloadJob> {
        val array = JSONArray(prefs.getString("jobs", "[]") ?: "[]")
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    DownloadJob(
                        id = o.getString("id"),
                        sourceUrl = o.getString("sourceUrl"),
                        title = o.getString("title"),
                        formatUrl = o.getString("formatUrl"),
                        status = DownloadStatus.valueOf(o.getString("status")),
                        progress = o.optInt("progress"),
                        downloadedBytes = o.optLong("downloadedBytes"),
                        totalBytes = o.optLong("totalBytes").takeIf { it > 0L }
                    )
                )
            }
        }.reversed()
    }

    fun create(url: String): DownloadJob {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            sourceUrl = url,
            title = titleFromUrl(url),
            formatUrl = url,
            status = DownloadStatus.QUEUED,
            progress = 0,
            downloadedBytes = 0L,
            totalBytes = null
        )
        save(job)
        val request = OneTimeWorkRequestBuilder<DirectDownloadWorker>()
            .setInputData(
                workDataOf(
                    DirectDownloadWorker.KEY_JOB_ID to job.id,
                    DirectDownloadWorker.KEY_URL to url,
                    DirectDownloadWorker.KEY_TITLE to job.title
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(10))
            .addTag("ahdownload:" + job.id)
            .build()
        WorkManager.getInstance(app).enqueue(request)
        return job
    }

    fun update(id: String, change: (DownloadJob) -> DownloadJob) {
        all().firstOrNull { it.id == id }?.let { save(change(it)) }
    }

    fun cancel(jobId: String) {
        WorkManager.getInstance(app).cancelAllWorkByTag("ahdownload:" + jobId)
        update(jobId) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    private fun save(job: DownloadJob) {
        val current = all().filterNot { it.id == job.id }.toMutableList()
        current.add(job)
        val array = JSONArray()
        current.forEach { j ->
            array.put(JSONObject().apply {
                put("id", j.id)
                put("sourceUrl", j.sourceUrl)
                put("title", j.title)
                put("formatUrl", j.formatUrl)
                put("status", j.status.name)
                put("progress", j.progress)
                put("downloadedBytes", j.downloadedBytes)
                put("totalBytes", j.totalBytes ?: 0L)
            })
        }
        prefs.edit().putString("jobs", array.toString()).apply()
    }

    private fun titleFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?')
            .ifBlank { "AHDownload file" }
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .take(120)
}
