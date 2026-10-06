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

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationId = id.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)

    override suspend fun doWork(): Result {
        val task = readTask() ?: return Result.failure()

        setForeground(createForegroundInfo(DownloadState.Preparing))

        val repository = FileDownloadRepository(applicationContext)
        val queue = PersistentDownloadQueue(repository)
        val engine = StreamingDownloadEngine(
            source = OkHttpDownloadByteStream(
                logger = diagnosticsLogger(),
                dynamicHeaders = ::dynamicHeadersFor,
            ),
            sink = LocalAtomicFileSink(),
        )
        val coordinator = DownloadCoordinator(
            engine = engine,
            queue = queue,
        )

        val diagnostics = diagnosticsLogger()
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

        val record = coordinator.execute(task) { state ->
            setForeground(createForegroundInfo(state))
        }

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
                    localFile.delete()
                    diagnostics.log(
                        DiagnosticLevel.INFO,
                        "DOWNLOAD_DESTINATION_COMMITTED",
                        "تم نقل الوسيط المكتمل إلى المسار الذي اختاره المستخدم",
                        "download.destination",
                        mapOf(
                            "task_id" to task.id,
                            "destination_mode" to "CUSTOM_DIRECTORY",
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
                    return Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to "destination_storage_error",
                            KEY_FAILURE_DETAIL to "تعذر الكتابة في مجلد التنزيل المحدد.",
                        ),
                    )
                }
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
                if (record.failureCode == "network_error" || record.failureCode == "http_error" && isRetryableHttp(record.failureDetail)) {
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
            DownloadStatus.QUEUED,
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING -> Result.failure(
                workDataOf(KEY_FAILURE_CODE to "non_terminal_state"),
            )
        }
    }

    private fun diagnosticsLogger(): com.ahdownload.app.diagnostics.PersistentDiagnosticLogger =
        (applicationContext as com.ahdownload.app.AHDownloadApplication).diagnosticLogger

    private fun dynamicHeadersFor(url: String): Map<String, String> {
        if (!isYouTubeMediaHost(url)) return emptyMap()
        val cookies = CookieManager.getInstance()
            .getCookie("https://www.youtube.com/")
            ?.takeIf { it.isNotBlank() }
        return buildMap {
            cookies?.let { put("Cookie", it) }
            put("Referer", "https://www.youtube.com/")
        }
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
        val requestHeaders = buildMap {
            inputData.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            inputData.getString(KEY_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            inputData.getString(KEY_ORIGIN)?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
        }

        return DownloadTask(
            id = taskId,
            sourceUrl = sourceUrl,
            destinationPath = destinationPath,
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
                    state !is DownloadState.Cancelled,
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

    private fun isRetryableHttp(detail: String?): Boolean {
        val code = detail?.toIntOrNull() ?: return false
        return code == 408 || code == 429 || code in 500..599
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_DESTINATION_PATH = "destination_path"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_REFERER = "referer"
        const val KEY_ORIGIN = "origin"
        const val KEY_FAILURE_CODE = "failure_code"
        const val KEY_FAILURE_DETAIL = "failure_detail"
        const val TAG = "ahdownload-download-worker"

        private const val CHANNEL_ID = "ahdownload_downloads"
    }
}
