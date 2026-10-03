package com.ahdownload.app.download

import android.webkit.MimeTypeMap
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.diagnostics.AppLogger
import com.ahdownload.app.domain.DownloadStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.TimeUnit

class DirectDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "download"
        val repo = DownloadRepository(applicationContext)
        return try {
            repo.update(jobId) { it.copy(status = DownloadStatus.DOWNLOADING) }
            AppLogger.info(applicationContext, "download.start", "job=$jobId")
            val dir = File(applicationContext.getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val extension = MimeTypeMap.getFileExtensionFromUrl(url).takeIf { it.isNotBlank() } ?: "bin"
            val safeTitle = title.replace(Regex("[\\/:*?\"<>|]"), "_").take(120)
            val target = File(dir, "$safeTitle.$extension")
            val part = File(dir, "$safeTitle.$extension.part")
            var existing = if (part.exists()) part.length() else 0L

            val request = Request.Builder().url(url).apply {
                if (existing > 0L) header("Range", "bytes=$existing-")
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    repo.update(jobId) { it.copy(status = DownloadStatus.FAILED) }
                    return if (response.code in 500..599) Result.retry() else Result.failure()
                }
                val contentType = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase()
                if (contentType == "text/html" || contentType == "application/xhtml+xml") {
                    repo.update(jobId) { it.copy(status = DownloadStatus.FAILED) }
                    AppLogger.error(applicationContext, "download.rejected_html", details = "job=$jobId contentType=$contentType")
                    return Result.failure()
                }
                val body = response.body
                val append = existing > 0L && response.code == 206
                if (!append) existing = 0L
                val length = body.contentLength()
                val total = if (length > 0L) existing + length else null
                repo.update(jobId) { it.copy(totalBytes = total, downloadedBytes = existing) }

                body.byteStream().use { input ->
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.setLength(existing)
                        raf.seek(existing)
                        val buffer = ByteArray(64 * 1024)
                        var done = existing
                        var checkpoint = existing
                        while (true) {
                            if (isStopped) return Result.retry()
                            val read = input.read(buffer)
                            if (read < 0) break
                            raf.write(buffer, 0, read)
                            done += read
                            if (done - checkpoint >= 256 * 1024L) {
                                val progress = total?.let { ((done * 100L) / it).toInt().coerceIn(0, 100) } ?: 0
                                repo.update(jobId) { it.copy(progress = progress, downloadedBytes = done) }
                                checkpoint = done
                            }
                        }
                        repo.update(jobId) { it.copy(progress = 100, downloadedBytes = done) }
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("finalize_failed")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                ?: "application/octet-stream"
            val published = StoragePublisher.publish(applicationContext, target, target.name, mime)
            if (published?.startsWith("content://") == true) target.delete()

            repo.update(jobId) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    downloadedBytes = if (target.exists()) target.length() else it.downloadedBytes,
                    outputUri = published
                )
            }
            AppLogger.info(applicationContext, "download.completed", "job=$jobId")
            Result.success()
        } catch (e: IOException) {
            repo.update(jobId) { it.copy(status = DownloadStatus.RETRYING) }
            AppLogger.error(applicationContext, "download.io_retry", e, "job=$jobId")
            Result.retry()
        } catch (e: Throwable) {
            repo.update(jobId) { it.copy(status = DownloadStatus.FAILED) }
            AppLogger.error(applicationContext, "download.failed", e, "job=$jobId")
            Result.failure()
        }
    }
}
