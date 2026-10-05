package com.ahdownload.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ahdownload.app.R
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
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
            source = OkHttpDownloadByteStream(),
            sink = LocalAtomicFileSink(),
        )
        val coordinator = DownloadCoordinator(
            engine = engine,
            queue = queue,
        )

        val record = coordinator.execute(task) { state ->
            setForeground(createForegroundInfo(state))
        }

        val diagnostics = (applicationContext as com.ahdownload.app.AHDownloadApplication).diagnosticLogger
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
            DownloadStatus.FAILED -> Result.failure(
                workDataOf(
                    KEY_FAILURE_CODE to record.failureCode,
                    KEY_FAILURE_DETAIL to record.failureDetail,
                ),
            )
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

    private fun readTask(): DownloadTask? {
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = inputData.getString(KEY_SOURCE_URL)?.takeIf { it.isNotBlank() } ?: return null
        val destinationPath =
            inputData.getString(KEY_DESTINATION_PATH)?.takeIf { it.isNotBlank() } ?: return null

        return DownloadTask(
            id = taskId,
            sourceUrl = sourceUrl,
            destinationPath = destinationPath,
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

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_DESTINATION_PATH = "destination_path"
        const val KEY_FAILURE_CODE = "failure_code"
        const val KEY_FAILURE_DETAIL = "failure_detail"
        const val TAG = "ahdownload-download-worker"

        private const val CHANNEL_ID = "ahdownload_downloads"
    }
}
