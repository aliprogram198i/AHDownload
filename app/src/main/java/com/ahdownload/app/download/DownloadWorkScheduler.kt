package com.ahdownload.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.app.settings.DownloadPreferencesStore
import com.ahdownload.domain.download.DownloadTask
import java.util.concurrent.TimeUnit

class DownloadWorkScheduler(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val controlStore = DownloadControlStore(appContext)
    private val preferencesStore = DownloadPreferencesStore(appContext)

    fun enqueue(task: DownloadTask) {
        enqueueInternal(task, forceRefresh = false)
    }

    private fun enqueueInternal(task: DownloadTask, forceRefresh: Boolean) {
        require(task.id.isNotBlank()) { "task.id must not be blank" }
        require(task.sourceUrl.isNotBlank()) { "task.sourceUrl must not be blank" }
        require(task.destinationPath.isNotBlank()) { "task.destinationPath must not be blank" }
        controlStore.clearPaused(task.id)

        val input = buildInputData(task, forceRefresh)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (preferencesStore.current().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .addTag(DownloadWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(task.id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun pause(taskId: String) {
        require(taskId.isNotBlank())
        controlStore.markPaused(taskId)
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
    }

    fun resume(record: DownloadRecord) {
        controlStore.clearPaused(record.task.id)
        enqueueInternal(record.task, forceRefresh = true)
    }

    fun cancel(taskId: String) {
        require(taskId.isNotBlank())
        controlStore.clearPaused(taskId)
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
    }

    fun clearControl(taskId: String) = controlStore.clearPaused(taskId)

    fun isPaused(taskId: String): Boolean = controlStore.isPaused(taskId)

    fun workInfo(taskId: String) =
        workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(taskId))

    private fun buildInputData(task: DownloadTask, forceRefresh: Boolean): Data =
        Data.Builder()
            .putString(DownloadWorker.KEY_TASK_ID, task.id)
            .putString(DownloadWorker.KEY_SOURCE_URL, task.sourceUrl)
            .putString(DownloadWorker.KEY_DESTINATION_PATH, task.destinationPath)
            .putString(DownloadWorker.KEY_DISPLAY_NAME, task.displayName)
            .putString(DownloadWorker.KEY_THUMBNAIL_URL, task.thumbnailUrl)
            .putString(DownloadWorker.KEY_CONTENT_FINGERPRINT, task.contentFingerprint)
            .putString(DownloadWorker.KEY_SESSION_COOKIE_HOST, task.sessionCookieHost)
            .putString(DownloadWorker.KEY_SOURCE_PAGE_URL, task.sourcePageUrl)
            .putString(DownloadWorker.KEY_MEDIA_KIND, task.mediaKind?.name)
            .putBoolean(DownloadWorker.KEY_FORCE_REFRESH, forceRefresh)
            .apply {
                task.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_USER_AGENT, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_REFERER, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_ORIGIN, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_ACCEPT, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Accept-Language", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_ACCEPT_LANGUAGE, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_SEC_FETCH_DEST, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Mode", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_SEC_FETCH_MODE, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Site", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_SEC_FETCH_SITE, it) }
            }
            .build()

    private fun uniqueWorkName(taskId: String): String = "download:$taskId"

    companion object {
        const val TAG = "ahdownload-download"
    }
}
