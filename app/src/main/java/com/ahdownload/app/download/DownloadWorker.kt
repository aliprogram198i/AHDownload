package com.ahdownload.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ahdownload.app.R
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.app.settings.SelectedDirectoryStorage
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.domain.download.DownloadCoordinator
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.download.LocalAtomicFileSink
import com.ahdownload.domain.download.OkHttpDownloadByteStream
import com.ahdownload.domain.download.PersistentDownloadQueue
import com.ahdownload.domain.download.StreamingDownloadEngine
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.CandidateRanker
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.feature.home.AndroidYouTubeSessionProvider
import com.ahdownload.feature.home.HomeResolver

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationId = id.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)

    override suspend fun doWork(): Result {
        var task = readTask() ?: return Result.failure()

        setForeground(createForegroundInfo(DownloadState.Preparing))

        val application = applicationContext as com.ahdownload.app.AHDownloadApplication
        val repository = application.downloadRepository
        val diagnostics = diagnosticsLogger()
        var refreshAttempted = false

        if (shouldRefreshYouTubeTask(task, repository)) {
            refreshAttempted = true
            val refreshed = refreshYouTubeTask(task, diagnostics)
            if (refreshed == null) {
                diagnostics.log(
                    DiagnosticLevel.ERROR,
                    "YOUTUBE_RETRY_REFRESH_FAILED",
                    "تعذر استخراج مصدر YouTube حديث لإعادة المحاولة",
                    "download.refresh",
                    mapOf(
                        "task_id" to task.id,
                        "run_attempt" to runAttemptCount.toString(),
                        "source_page_present" to (!task.sourcePageUrl.isNullOrBlank()).toString(),
                    ),
                    null,
                )
                return Result.failure(
                    workDataOf(
                        KEY_FAILURE_CODE to "youtube_refresh_failed",
                        KEY_FAILURE_DETAIL to "تعذر تحديث مصدر YouTube قبل إعادة المحاولة.",
                    ),
                )
            }
            task = refreshed
            diagnostics.log(
                DiagnosticLevel.INFO,
                "YOUTUBE_RETRY_REFRESH_APPLIED",
                "تم تحديث مصدر YouTube قبل إعادة المحاولة",
                "download.refresh",
                mapOf(
                    "task_id" to task.id,
                    "run_attempt" to runAttemptCount.toString(),
                    "source_host" to hostOf(task.sourceUrl),
                ),
                null,
            )
        }

        val queue = PersistentDownloadQueue(repository)
        val engine = StreamingDownloadEngine(
            source = OkHttpDownloadByteStream(
                logger = diagnosticsLogger(),
                dynamicHeaders = { url, _ -> dynamicHeadersFor(url, task.sessionCookieHost) },
            ),
            sink = LocalAtomicFileSink(),
        )
        val controlStore = DownloadControlStore(applicationContext)
        val coordinator = DownloadCoordinator(
            engine = engine,
            queue = queue,
            isPauseRequested = { controlStore.isPaused(task.id) },
        )

        diagnostics.log(
            DiagnosticLevel.INFO,
            "DOWNLOAD_STARTED",
            "بدء تنفيذ مهمة التنزيل",
            "download.worker",
            mapOf(
                "task_id" to task.id,
                "source_host" to hostOf(task.sourceUrl),
                "destination_mode" to if (DownloadLocationStore(applicationContext).persistedUri() != null) "CUSTOM_DIRECTORY" else "APP_DEFAULT",
                "youtube_session_context" to isYouTubeMediaHost(task.sourceUrl).toString(),
                "request_context_present" to task.requestHeaders.keys.sorted().joinToString(",").ifBlank { "none" },
            ),
            null,
        )

        val record = try {
            coordinator.execute(task) { state ->
                setForeground(createForegroundInfo(state))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            diagnostics.log(
                DiagnosticLevel.INFO,
                "DOWNLOAD_WORK_CANCELLED",
                if (controlStore.isPaused(task.id)) "تم إيقاف التنزيل مؤقتًا" else "تم إلغاء مهمة التنزيل",
                "download.worker",
                mapOf("task_id" to task.id, "paused_request" to controlStore.isPaused(task.id).toString()),
                cancelled,
            )
            throw cancelled
        }

        var completedRecord = record
        if (record.status == DownloadStatus.COMPLETED) {
            val locationStore = DownloadLocationStore(applicationContext)
            val treeUri = locationStore.persistedUri()
            if (treeUri != null) {
                val localFile = java.io.File(task.destinationPath)
                val copied = runCatching {
                    SelectedDirectoryStorage.copyFromLocal(
                        context = applicationContext,
                        source = localFile,
                        treeUri = treeUri,
                        displayName = localFile.name,
                    )
                }
                if (copied.isSuccess) {
                    val destinationUri = copied.getOrThrow()
                    localFile.delete()
                    completedRecord = record.copy(
                        destinationUri = destinationUri.toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    diagnostics.log(
                        DiagnosticLevel.INFO,
                        "DOWNLOAD_DESTINATION_COMMITTED",
                        "تم نقل الوسيط المكتمل إلى المسار الذي اختاره المستخدم",
                        "download.destination",
                        mapOf(
                            "task_id" to task.id,
                            "destination_mode" to "CUSTOM_DIRECTORY",
                            "destination_uri_present" to "true",
                            "source_deleted_after_copy" to "true",
                        ),
                        null,
                    )
                } else {
                    diagnostics.log(
                        DiagnosticLevel.ERROR,
                        "DOWNLOAD_DESTINATION_COPY_FAILED",
                        "فشل حفظ الوسيط المكتمل في المسار المحدد",
                        "download.destination",
                        mapOf(
                            "task_id" to task.id,
                            "destination_mode" to "CUSTOM_DIRECTORY",
                            "local_recovery_file_present" to localFile.exists().toString(),
                            "failure" to (copied.exceptionOrNull()?.message ?: "unknown"),
                        ),
                        copied.exceptionOrNull(),
                    )
                    persistDestinationFailure(record, "تعذر الكتابة في مجلد التنزيل المحدد.")
                    return Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to "destination_storage_error",
                            KEY_FAILURE_DETAIL to "تعذر الكتابة في مجلد التنزيل المحدد.",
                        ),
                    )
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val localFile = java.io.File(task.destinationPath)
                val published = MediaStorePublisher(applicationContext).publish(localFile)
                if (published.isFailure) {
                    val detail = "تم تنزيل الملف، لكن تعذر حفظه في مكتبة الوسائط. بقيت نسخة استرداد محلية."
                    persistDestinationFailure(record, detail)
                    return Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to "destination_storage_error",
                            KEY_FAILURE_DETAIL to detail,
                        ),
                    )
                }
                completedRecord = record.copy(
                    destinationUri = published.getOrThrow().toString(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
            if (completedRecord !== record) {
                repository.upsert(completedRecord)
            }
        }

        if (record.status == DownloadStatus.FAILED) {
            diagnostics.log(
                level = DiagnosticLevel.ERROR,
                type = "DOWNLOAD",
                reason = record.failureCode ?: "FAILED",
                operation = "download.worker",
                context = buildMap {
                    put("task_id", task.id)
                    record.failureDetail?.let { put("failure_detail", it) }
                },
                throwable = null,
            )
        } else if (record.status == DownloadStatus.CANCELLED) {
            diagnostics.log(
                level = DiagnosticLevel.WARNING,
                type = "DOWNLOAD",
                reason = "CANCELLED",
                operation = "download.worker",
                context = mapOf("task_id" to task.id),
                throwable = null,
            )
        }

        return when (record.status) {
            DownloadStatus.COMPLETED -> Result.success()
            DownloadStatus.FAILED -> {
                val retryableYouTube403 =
                    !refreshAttempted &&
                        runAttemptCount == 0 &&
                        isYouTubeTask(task) &&
                        record.failureCode == "http_error" &&
                        record.failureDetail == "403"

                if (
                    record.failureCode == "network_error" ||
                    record.failureCode == "http_error" && isRetryableHttp(record.failureDetail) ||
                    retryableYouTube403
                ) {
                    Result.retry()
                } else {
                    Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to record.failureCode,
                            KEY_FAILURE_DETAIL to record.failureDetail,
                        ),
                    )
                }
            }
            DownloadStatus.CANCELLED -> Result.failure(
                workDataOf(KEY_FAILURE_CODE to "cancelled"),
            )
            DownloadStatus.PAUSED -> Result.failure(
                workDataOf(KEY_FAILURE_CODE to "paused"),
            )
            DownloadStatus.QUEUED,
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING -> Result.failure(
                workDataOf(KEY_FAILURE_CODE to "non_terminal_state"),
            )
        }
    }

    private suspend fun persistDestinationFailure(
        record: com.ahdownload.domain.download.DownloadRecord,
        detail: String,
    ) {
        runCatching {
            (applicationContext as com.ahdownload.app.AHDownloadApplication).downloadRepository.upsert(
                record.copy(
                    status = DownloadStatus.FAILED,
                    failureCode = "destination_storage_error",
                    failureDetail = detail,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun diagnosticsLogger(): com.ahdownload.app.diagnostics.PersistentDiagnosticLogger =
        (applicationContext as com.ahdownload.app.AHDownloadApplication).diagnosticLogger

    private fun dynamicHeadersFor(url: String, sessionCookieHost: String?): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        val host = hostOf(url)
        return buildMap {
            when {
                isYouTubeMediaHost(url) -> {
                    put("Referer", "https://www.youtube.com/")
                    CookieManager.getInstance().getCookie("https://www.youtube.com/")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("Cookie", it) }
                }
                !sessionCookieHost.isNullOrBlank() && hostMatchesSession(host, sessionCookieHost) -> {
                    cookieManager.getCookie("https://$sessionCookieHost/")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("Cookie", it) }
                }
                host != "invalid" -> {
                    cookieManager.getCookie("https://$host/")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("Cookie", it) }
                }
            }
        }
    }

    private fun hostMatchesSession(mediaHost: String, sessionHost: String): Boolean {
        val normalized = sessionHost.lowercase().removePrefix("www.")
        return mediaHost == normalized || mediaHost.endsWith("." + normalized)
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: "invalid"

    private fun isYouTubeMediaHost(url: String): Boolean {
        val host = hostOf(url)
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    private fun readTask(): DownloadTask? {
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = inputData.getString(KEY_SOURCE_URL)?.takeIf { it.isNotBlank() } ?: return null
        val destinationPath =
            inputData.getString(KEY_DESTINATION_PATH)?.takeIf { it.isNotBlank() } ?: return null
        val displayName = inputData.getString(KEY_DISPLAY_NAME)
        val contentFingerprint = inputData.getString(KEY_CONTENT_FINGERPRINT).orEmpty()
        val sessionCookieHost = inputData.getString(KEY_SESSION_COOKIE_HOST)
        val sourcePageUrl = inputData.getString(KEY_SOURCE_PAGE_URL)
        val mediaKind = inputData.getString(KEY_MEDIA_KIND)
            ?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
        val requestHeaders = buildMap {
            inputData.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            inputData.getString(KEY_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            inputData.getString(KEY_ORIGIN)?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
            inputData.getString(KEY_ACCEPT)?.takeIf { it.isNotBlank() }?.let { put("Accept", it) }
            inputData.getString(KEY_ACCEPT_LANGUAGE)?.takeIf { it.isNotBlank() }?.let { put("Accept-Language", it) }
            inputData.getString(KEY_SEC_FETCH_DEST)?.takeIf { it.isNotBlank() }?.let { put("Sec-Fetch-Dest", it) }
            inputData.getString(KEY_SEC_FETCH_MODE)?.takeIf { it.isNotBlank() }?.let { put("Sec-Fetch-Mode", it) }
            inputData.getString(KEY_SEC_FETCH_SITE)?.takeIf { it.isNotBlank() }?.let { put("Sec-Fetch-Site", it) }
        }

        return DownloadTask(
            id = taskId,
            sourceUrl = sourceUrl,
            destinationPath = destinationPath,
            displayName = displayName,
            contentFingerprint = contentFingerprint,
            sessionCookieHost = sessionCookieHost,
            sourcePageUrl = sourcePageUrl,
            mediaKind = mediaKind,
            requestHeaders = requestHeaders,
        )
    }

    private fun createForegroundInfo(state: DownloadState): ForegroundInfo {
        ensureNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ahdownload)
            .setContentTitle("AHDownload")
            .setContentText(state.toNotificationText())
            .setOngoing(
                state !is DownloadState.Completed &&
                    state !is DownloadState.Failed &&
                    state !is DownloadState.Cancelled &&
                    state !is DownloadState.Paused,
            )
            .setOnlyAlertOnce(true)
            .setProgress(
                state.totalBytesOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                state.bytesDownloadedOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                state.totalBytesOrNull() == null && state !is DownloadState.Completed,
            )
            .addAction(
                android.R.drawable.ic_delete,
                "إلغاء",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "تنزيلات AHDownload",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "حالة تنزيلات الملفات والوسائط"
            },
        )
    }

    private fun DownloadState.toNotificationText(): String = when (this) {
        DownloadState.Queued -> "في قائمة الانتظار"
        DownloadState.Preparing -> "جاري تجهيز التنزيل"
        is DownloadState.Downloading -> {
            totalBytes?.let { total ->
                val percent = if (total > 0) {
                    (bytesDownloaded * 100 / total).coerceIn(0L, 100L)
                } else {
                    0L
                }
                "جاري التنزيل — $percent%"
            } ?: "جاري التنزيل"
        }
        DownloadState.Paused -> "تم الإيقاف المؤقت"
        DownloadState.Completed -> "اكتمل التنزيل"
        is DownloadState.Failed -> "فشل التنزيل"
        DownloadState.Cancelled -> "تم إلغاء التنزيل"
    }

    private fun DownloadState.totalBytesOrNull(): Long? = when (this) {
        is DownloadState.Downloading -> totalBytes
        else -> null
    }

    private fun DownloadState.bytesDownloadedOrNull(): Long? = when (this) {
        is DownloadState.Downloading -> bytesDownloaded
        else -> null
    }

    private suspend fun shouldRefreshYouTubeTask(
        task: DownloadTask,
        repository: com.ahdownload.app.download.FileDownloadRepository,
    ): Boolean {
        if (!isYouTubeTask(task)) return false

        val forced = inputData.getBoolean(KEY_FORCE_REFRESH, false) && runAttemptCount == 0
        if (forced) return true
        if (runAttemptCount != 1) return false

        val previous = repository.get(task.id) ?: return false
        return previous.failureCode == "http_error" && previous.failureDetail == "403"
    }

    private fun isYouTubeTask(task: DownloadTask): Boolean {
        val pageUrl = task.sourcePageUrl ?: return false
        if (task.mediaKind !in setOf(MediaKind.Video, MediaKind.Audio)) return false
        return LinkAnalyzer().analyze(pageUrl)?.platform == MediaPlatform.YouTube
    }

    private suspend fun refreshYouTubeTask(
        task: DownloadTask,
        diagnostics: PersistentDiagnosticLogger,
    ): DownloadTask? {
        val pageUrl = task.sourcePageUrl
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val link = LinkAnalyzer().analyze(pageUrl)?.takeIf { it.platform == MediaPlatform.YouTube }
            ?: return null
        val expectedKind = task.mediaKind ?: return null

        val resolver = HomeResolver(
            logger = diagnostics,
            sessionProvider = AndroidYouTubeSessionProvider(applicationContext),
        )

        val resolved = resolver.resolve(link, operationId = id.toString())
        if (resolved !is ResolverResult.Success) return null

        val candidates = CandidateRanker()
            .rank(resolved.candidates, requestedKind = expectedKind)
            .take(4)

        for (candidate in candidates) {
            val validation = resolver.validate(candidate, operationId = id.toString())
            if (validation is com.ahdownload.domain.validation.CandidateValidationResult.Valid) {
                return task.copy(
                    sourceUrl = validation.finalUrl,
                    sourcePageUrl = link.normalizedUrl,
                    mediaKind = expectedKind,
                    sessionCookieHost = validation.candidate.sessionCookieHost,
                    requestHeaders = validation.candidate.requestHeaders.filterKeys { key ->
                        !key.equals("Cookie", ignoreCase = true) &&
                            (key.equals("User-Agent", ignoreCase = true) ||
                                key.equals("Referer", ignoreCase = true) ||
                                key.equals("Origin", ignoreCase = true) ||
                                key.equals("Accept", ignoreCase = true) ||
                                key.equals("Accept-Language", ignoreCase = true) ||
                                key.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                                key.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                                key.equals("Sec-Fetch-Site", ignoreCase = true))
                    },
                )
            }
        }

        return null
    }

    private fun isRetryableHttp(detail: String?): Boolean {
        val code = detail?.toIntOrNull() ?: return false
        return code == 408 || code == 429 || code in 500..599
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_DESTINATION_PATH = "destination_path"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_CONTENT_FINGERPRINT = "content_fingerprint"
        const val KEY_SESSION_COOKIE_HOST = "session_cookie_host"
        const val KEY_SOURCE_PAGE_URL = "source_page_url"
        const val KEY_MEDIA_KIND = "media_kind"
        const val KEY_FORCE_REFRESH = "force_refresh"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_REFERER = "referer"
        const val KEY_ORIGIN = "origin"
        const val KEY_ACCEPT = "accept"
        const val KEY_ACCEPT_LANGUAGE = "accept_language"
        const val KEY_SEC_FETCH_DEST = "sec_fetch_dest"
        const val KEY_SEC_FETCH_MODE = "sec_fetch_mode"
        const val KEY_SEC_FETCH_SITE = "sec_fetch_site"
        const val KEY_FAILURE_CODE = "failure_code"
        const val KEY_FAILURE_DETAIL = "failure_detail"
        const val TAG = "ahdownload-download-worker"

        private const val CHANNEL_ID = "ahdownload_downloads"
    }
}
