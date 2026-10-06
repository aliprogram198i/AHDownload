package com.ahdownload.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ahdownload.domain.download.DownloadTask
import java.util.concurrent.TimeUnit

class DownloadWorkScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(task: DownloadTask) {
        require(task.id.isNotBlank()) { "task.id must not be blank" }
        require(task.sourceUrl.isNotBlank()) { "task.sourceUrl must not be blank" }
        require(task.destinationPath.isNotBlank()) { "task.destinationPath must not be blank" }

        val input = Data.Builder()
            .putString(DownloadWorker.KEY_TASK_ID, task.id)
            .putString(DownloadWorker.KEY_SOURCE_URL, task.sourceUrl)
            .putString(DownloadWorker.KEY_DESTINATION_PATH, task.destinationPath)
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

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
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

    fun cancel(taskId: String) {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
    }

    fun workInfo(taskId: String) =
        workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(taskId))

    private fun uniqueWorkName(taskId: String): String = "download:$taskId"

    companion object {
        const val TAG = "ahdownload-download"
    }
}
