package com.ahdownload.app.download

import android.webkit.MimeTypeMap
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import com.ahdownload.app.MainActivity
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.diagnostics.AppLogger
import com.ahdownload.app.data.MediaUrlRefresher
import com.ahdownload.app.data.MediaValidator
import com.ahdownload.app.domain.DownloadStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
import java.nio.ByteBuffer

class DirectDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_EXTENSION = "extension"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_MERGE_REQUIRED = "merge_required"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_AUDIO_EXTENSION = "audio_extension"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "download"
        val requestedExtension = inputData.getString(KEY_EXTENSION).orEmpty()
        val sourceUrl = inputData.getString(KEY_SOURCE_URL).orEmpty()
        val mergeRequired = inputData.getBoolean(KEY_MERGE_REQUIRED, false)
        val audioUrl = inputData.getString(KEY_AUDIO_URL).orEmpty()
        val audioExtension = inputData.getString(KEY_AUDIO_EXTENSION).orEmpty()
        val repo = DownloadRepository.get(applicationContext)
        return try {
            clearFailureState(repo, jobId)
            repo.update(jobId) { it.copy(status = DownloadStatus.DOWNLOADING) }
            notifyProgress(jobId, title, 0, null, 0L)
            AppLogger.info(applicationContext, "download.start", "job=$jobId")
            val dir = File(applicationContext.getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val extension = requestedExtension
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getFileExtensionFromUrl(url).lowercase().takeIf { it.isNotBlank() }
                ?: "bin"
            val safeTitle = title.replace(Regex("[\\/:*?\"<>|]"), "_").take(120)
            val target = uniqueTarget(dir, safeTitle, extension)
            val part = File(dir, "$safeTitle.${target.name.substringAfterLast(".")}.part")
            var existing = if (part.exists()) part.length() else 0L

            if (mergeRequired && audioUrl.isNotBlank()) {
                AppLogger.info(applicationContext, "download.merge_start", "job=$jobId")
                downloadMergedMedia(jobId, title, url, audioUrl, sourceUrl,
                    File(dir, "$safeTitle.video.part"),
                    File(dir, "$safeTitle." + audioExtension.ifBlank { "m4a" } + ".part"),
                    target, repo)
                MediaValidator.validateFile(target, "mp4").getOrElse {
                    target.delete()
                    throw IOException(it.message ?: "MEDIA_VALIDATION_FAILED")
                }
                val published = StoragePublisher.publish(applicationContext, target, target.name, "video/mp4")
                if (published?.startsWith("content://") == true) target.delete()
                repo.update(jobId) { it.copy(status = DownloadStatus.COMPLETED, progress = 100,
                    downloadedBytes = if (target.exists()) target.length() else it.downloadedBytes,
                    totalBytes = if (target.exists()) target.length() else it.totalBytes,
                    outputUri = published) }
                AppLogger.info(applicationContext, "download.completed", "job=$jobId mode=mux")
                notifyCompleted(jobId, title)
                return Result.success()
            }

            val requestBuilder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
            if (sourceUrl.isNotBlank()) requestBuilder.header("Referer", sourceUrl)
            val sourceHost = runCatching { android.net.Uri.parse(sourceUrl).host?.lowercase() }.getOrNull().orEmpty()
            if (sourceHost == "instagram.com" || sourceHost.endsWith(".instagram.com") ||
                sourceHost == "youtube.com" || sourceHost.endsWith(".youtube.com") ||
                sourceHost == "facebook.com" || sourceHost.endsWith(".facebook.com") ||
                sourceHost == "fb.watch") {
                runCatching { CookieManager.getInstance().getCookie(sourceUrl) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { requestBuilder.header("Cookie", it) }
            }
            if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 416 && existing > 0L) {
                        part.delete()
                        repo.update(jobId) { it.copy(status = DownloadStatus.RETRYING, downloadedBytes = 0L, progress = 0, speedBytesPerSec = 0L, etaSeconds = null, errorCode = "RANGE_RESET") }
                        notifyProgress(jobId, title, 0, null, 0L)
                        AppLogger.info(applicationContext, "download.range_reset", "job=$jobId")
                        return Result.retry()
                    }
                    if (response.code in setOf(401, 403, 410) && sourceUrl.isNotBlank()) {
                        val fresh = MediaUrlRefresher(applicationContext)
                            .refresh(sourceUrl, extension, false)
                            .getOrNull()
                        if (fresh != null && fresh.url.isNotBlank() && fresh.url != url) {
                            val settings = applicationContext.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
                            val constraints = androidx.work.Constraints.Builder()
                                .setRequiredNetworkType(
                                    if (settings.getBoolean("wifi_only", false))
                                        androidx.work.NetworkType.UNMETERED
                                    else androidx.work.NetworkType.CONNECTED
                                )
                                .build()
                            val request = androidx.work.OneTimeWorkRequestBuilder<DirectDownloadWorker>()
                                .setInputData(androidx.work.workDataOf(
                                    KEY_JOB_ID to jobId,
                                    KEY_URL to fresh.url,
                                    KEY_TITLE to title,
                                    KEY_EXTENSION to extension,
                                    KEY_SOURCE_URL to sourceUrl,
                                    KEY_MERGE_REQUIRED to false,
                                    KEY_AUDIO_URL to "",
                                    KEY_AUDIO_EXTENSION to ""
                                ))
                                .setConstraints(constraints)
                                .addTag("ahdownload:$jobId")
                                .build()
                            repo.update(jobId) { it.copy(status = DownloadStatus.QUEUED) }
                            androidx.work.WorkManager.getInstance(applicationContext).enqueue(request)
                            AppLogger.info(applicationContext, "download.url_refreshed", "job=$jobId reason=http_" + response.code)
                            return Result.success()
                        }
                    }
                    markFailed(repo, jobId, "HTTP_${response.code}")
                    return if (response.code in 500..599 && runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) { Result.retry() } else { notifyFailed(jobId, title, "فشل الاتصال بالمصدر"); Result.failure() }
                }
                val contentType = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase()
                if (contentType == "text/html" || contentType == "application/xhtml+xml") {
                    markFailed(repo, jobId, "HTML_RESPONSE")
                    AppLogger.error(applicationContext, "download.rejected_html", details = "job=$jobId contentType=$contentType")
                    notifyFailed(jobId, title, "المصدر أعاد صفحة ويب بدلاً من ملف وسائط")
                    return Result.failure()
                }
                val body = response.body
                val append = if (existing > 0L && response.code == 206) {
                    val rangeStart = response.header("Content-Range")
                        ?.substringAfter("bytes ", "")
                        ?.substringBefore("-")
                        ?.toLongOrNull()
                    rangeStart == existing
                } else false
                if (!append) existing = 0L
                val length = body.contentLength()
                val total = if (length > 0L) existing + length else null
                val startedNanos = System.nanoTime()
                repo.update(jobId) { it.copy(totalBytes = total, downloadedBytes = existing, speedBytesPerSec = 0L, etaSeconds = null) }

                body.byteStream().use { input ->
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.setLength(existing)
                        raf.seek(existing)
                        val buffer = ByteArray(64 * 1024)
                        var done = existing
                        var checkpoint = existing
                        var lastNotifyBytes = existing
                        var lastNotifyNanos = startedNanos
                        while (true) {
                            if (isStopped) return Result.retry()
                            val read = input.read(buffer)
                            if (read < 0) break
                            raf.write(buffer, 0, read)
                            done += read
                            if (done - checkpoint >= 256 * 1024L) {
                                val progress = total?.let { ((done * 100L) / it).toInt().coerceIn(0, 100) } ?: 0
                                val now = System.nanoTime()
                                val elapsedSec = ((now - startedNanos) / 1_000_000_000L).coerceAtLeast(1L)
                                val speed = ((done - existing) / elapsedSec).coerceAtLeast(0L)
                                val eta = if (speed > 0L && total != null) ((total - done).coerceAtLeast(0L) / speed) else null
                                repo.update(jobId) { it.copy(progress = progress, downloadedBytes = done, speedBytesPerSec = speed, etaSeconds = eta) }
                                if (done - lastNotifyBytes >= 512 * 1024L || now - lastNotifyNanos >= 2_000_000_000L) {
                                    notifyProgress(jobId, title, progress, eta, speed)
                                    lastNotifyBytes = done
                                    lastNotifyNanos = now
                                }
                                checkpoint = done
                            }
                        }
                        val elapsedSec = ((System.nanoTime() - startedNanos) / 1_000_000_000L).coerceAtLeast(1L)
                        val speed = ((done - existing) / elapsedSec).coerceAtLeast(0L)
                        repo.update(jobId) { it.copy(progress = 100, downloadedBytes = done, speedBytesPerSec = speed, etaSeconds = 0L) }
                        notifyProgress(jobId, title, 100, 0L, speed)
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("finalize_failed")
            MediaValidator.validateFile(target, extension).getOrElse {
                target.delete()
                throw IOException(it.message ?: "MEDIA_VALIDATION_FAILED")
            }
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
            notifyCompleted(jobId, title)
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

    private fun downloadMergedMedia(jobId: String, title: String, videoUrl: String, audioUrl: String, sourceUrl: String, videoFile: File, audioFile: File, target: File, repo: DownloadRepository) {
        downloadStream(videoUrl, sourceUrl, videoFile)
        repo.update(jobId) { it.copy(progress = 50, downloadedBytes = videoFile.length()) }
        downloadStream(audioUrl, sourceUrl, audioFile)
        repo.update(jobId) { it.copy(progress = 80, downloadedBytes = videoFile.length() + audioFile.length()) }
        try {
            muxMp4(videoFile, audioFile, target)
        } catch (e: Throwable) {
            AppLogger.error(applicationContext, "download.merge_failed", e, "job=$jobId")
            throw e
        } finally {
            videoFile.delete()
            audioFile.delete()
        }
        repo.update(jobId) { it.copy(progress = 100, downloadedBytes = target.length()) }
        AppLogger.info(applicationContext, "download.merge_completed", "job=$jobId bytes=${target.length()}")
    }

    private fun downloadStream(url: String, sourceUrl: String, target: File) {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "*/*")
        if (sourceUrl.isNotBlank()) b.header("Referer", sourceUrl)
        runCatching { CookieManager.getInstance().getCookie(sourceUrl) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        client.newCall(b.build()).execute().use { response ->
            if (!response.isSuccessful) error("MEDIA_DOWNLOAD_HTTP_${response.code}")
            val type = response.header("Content-Type").orEmpty().substringBefore(";").lowercase()
            if (type == "text/html" || type == "application/xhtml+xml") error("MEDIA_DOWNLOAD_HTML")
            val body = response.body ?: error("MEDIA_DOWNLOAD_EMPTY")
            target.parentFile?.mkdirs(); target.outputStream().use { output -> body.byteStream().use { input -> input.copyTo(output, 64 * 1024) } }
        }
    }

    private fun muxMp4(videoFile: File, audioFile: File, target: File) {
        val ve = MediaExtractor(); val ae = MediaExtractor(); var muxer: MediaMuxer? = null
        try {
            ve.setDataSource(videoFile.absolutePath); ae.setDataSource(audioFile.absolutePath)
            val vt = findTrack(ve, true); val at = findTrack(ae, false)
            require(vt >= 0) { "MUX_VIDEO_TRACK_NOT_FOUND" }; require(at >= 0) { "MUX_AUDIO_TRACK_NOT_FOUND" }
            target.parentFile?.mkdirs(); if (target.exists()) target.delete()
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val ov = muxer.addTrack(ve.getTrackFormat(vt)); val oa = muxer.addTrack(ae.getTrackFormat(at)); muxer.start()
            copyTrack(ve, vt, muxer, ov); copyTrack(ae, at, muxer, oa)
        } finally { runCatching { muxer?.stop() }; runCatching { muxer?.release() }; ve.release(); ae.release() }
    }

    private fun findTrack(extractor: MediaExtractor, video: Boolean): Int {
        for (i in 0 until extractor.trackCount) { val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty(); if (if (video) mime.startsWith("video/") else mime.startsWith("audio/")) return i }
        return -1
    }

    private fun copyTrack(extractor: MediaExtractor, track: Int, muxer: MediaMuxer, outputTrack: Int) {
        extractor.selectTrack(track); val buffer = ByteBuffer.allocate(1024 * 1024); val info = android.media.MediaCodec.BufferInfo()
        while (true) { val size = extractor.readSampleData(buffer, 0); if (size < 0) break; info.offset = 0; info.size = size; info.presentationTimeUs = extractor.sampleTime; info.flags = extractor.sampleFlags; muxer.writeSampleData(outputTrack, buffer, info); extractor.advance(); buffer.clear() }
    }
    private fun uniqueTarget(dir: File, safeTitle: String, extension: String): File {
        val first = File(dir, "$safeTitle.$extension")
        if (!first.exists() && !File(dir, "$safeTitle.$extension.part").exists()) return first
        var index = 2
        while (true) {
            val candidate = File(dir, "$safeTitle ($index).$extension")
            val part = File(dir, "$safeTitle ($index).$extension.part")
            if (!candidate.exists() && !part.exists()) return candidate
            index++
        }
    }

    private fun clearFailureState(repo: DownloadRepository, jobId: String) {
        repo.update(jobId) { it.copy(errorCode = null, speedBytesPerSec = 0L, etaSeconds = null) }
    }

    private fun markFailed(repo: DownloadRepository, jobId: String, code: String) {
        repo.update(jobId) { it.copy(status = DownloadStatus.FAILED, errorCode = code, speedBytesPerSec = 0L, etaSeconds = null) }
    }

    private fun notificationAllowed(): Boolean {
        val prefs = applicationContext.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notifications", true)) return false
        return Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun notifyProgress(jobId: String, title: String, progress: Int, etaSeconds: Long?, speedBytesPerSec: Long) {
        if (!notificationAllowed()) return
        val intent = android.content.Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, jobId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (speedBytesPerSec > 0L) "جارٍ التنزيل • " + progress + "% • " + formatRate(speedBytesPerSec) + (etaSeconds?.let { " • متبقٍ " + formatDuration(it) } ?: "") else "جارٍ التنزيل • " + progress + "%"
        val notification = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(com.ahdownload.app.R.drawable.ic_ahdownload).setContentTitle(title).setContentText(text).setContentIntent(pending).setOnlyAlertOnce(true).setOngoing(progress < 100).setProgress(100, progress.coerceIn(0, 100), false).build()
        androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(jobId.hashCode(), notification)
    }

    private fun notifyFailed(jobId: String, title: String, reason: String) {
        if (!notificationAllowed()) return
        val intent = android.content.Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, jobId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(com.ahdownload.app.R.drawable.ic_ahdownload).setContentTitle("فشل التنزيل").setContentText(title + " • " + reason).setContentIntent(pending).setAutoCancel(true).build()
        androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(jobId.hashCode(), notification)
    }

    private fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"
    private fun formatDuration(seconds: Long): String { val h=seconds/3600; val m=(seconds%3600)/60; val s=seconds%60; return if(h>0) String.format(java.util.Locale.US,"%d:%02d:%02d",h,m,s) else String.format(java.util.Locale.US,"%d:%02d",m,s) }
    private fun formatBytes(value: Long): String { if(value<1024)return "$value B"; var n=value.toDouble(); val units=arrayOf("KB","MB","GB","TB"); var i=-1; while(n>=1024&&i<units.lastIndex){n/=1024;i++}; return String.format(java.util.Locale.US,"%.1f %s",n,units[i]) }
    private fun notifyCompleted(jobId: String, title: String) {
        val prefs = applicationContext.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notifications", true)) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val intent = android.content.Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext, jobId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(com.ahdownload.app.R.drawable.ic_ahdownload)
            .setContentTitle("اكتمل التنزيل")
            .setContentText(title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(applicationContext)
            .notify(jobId.hashCode(), notification)
    }
}
