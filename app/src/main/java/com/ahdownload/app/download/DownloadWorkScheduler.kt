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
import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadTemporaryFiles
import androidx.work.WorkInfo
import com.ahdownload.app.AHDownloadApplication
import com.ahdownload.app.settings.DownloadPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DownloadWorkScheduler(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val controlStore = DownloadControlStore(appContext)
    private val preferencesStore = DownloadPreferencesStore(appContext)
    private val repository: DownloadRepository? = (appContext as? AHDownloadApplication)?.downloadRepository
    private val controlStateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes scheduler-side actions so a concurrent enqueue cannot run after a pause/cancel. */
    private val controlOperationLock = Any()

    fun enqueue(task: DownloadTask) {
        enqueueInternal(task, forceRefresh = false)
    }

    private fun enqueueInternal(task: DownloadTask, forceRefresh: Boolean) {
        synchronized(controlOperationLock) { enqueueLocked(task, forceRefresh) }
    }

    private fun enqueueLocked(task: DownloadTask, forceRefresh: Boolean) {
        require(task.id.isNotBlank()) { "task.id must not be blank" }
        require(task.sourceUrl.isNotBlank()) { "task.sourceUrl must not be blank" }
        require(task.destinationPath.isNotBlank()) { "task.destinationPath must not be blank" }
        controlStore.markActive(task.id)

        val input = buildInputData(task, forceRefresh)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (preferencesStore.read().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
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
            if (forceRefresh) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun pause(taskId: String) {
        require(taskId.isNotBlank())
        val intent = synchronized(controlOperationLock) {
            controlStore.markPaused(taskId).also {
                workManager.cancelUniqueWork(uniqueWorkName(taskId))
            }
        }
        persistControlStatus(taskId, DownloadStatus.PAUSED, intent)
    }

    fun resume(record: DownloadRecord) {
        // enqueueInternal marks the newest intent ACTIVE before replacing cancelled/paused work.
        enqueueInternal(record.task, forceRefresh = true)
    }

    fun cancel(taskId: String) {
        require(taskId.isNotBlank())
        val intent = synchronized(controlOperationLock) {
            controlStore.markCancelled(taskId).also {
                workManager.cancelUniqueWork(uniqueWorkName(taskId))
            }
        }
        persistControlStatus(taskId, DownloadStatus.CANCELLED, intent)
    }

    /**
     * Reconcile persisted active records against WorkManager on app startup. Only orphaned active
     * records are restored; intentionally paused/cancelled tasks are never auto-resumed.
     */
    fun reconcileOnStartup() {
        val store = repository ?: return
        controlStateScope.launch {
            val records = runCatching { store.listHistory() }.getOrElse { return@launch }
            records.forEach { record ->
                if (!shouldReconcileDownloadAtStartup(
                        record.status,
                        controlStore.snapshot(record.task.id)?.intent,
                        hasUnfinishedWork = false,
                    )
                ) return@forEach

                val workInfos = runCatching {
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName(record.task.id)).get()
                }.getOrElse { return@forEach }
                val hasUnfinishedWork = workInfos.any { info ->
                    info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.BLOCKED ||
                        info.state == WorkInfo.State.RUNNING
                }
                val current = runCatching { store.get(record.task.id) }.getOrNull() ?: return@forEach
                if (!shouldReconcileDownloadAtStartup(
                        current.status,
                        controlStore.snapshot(current.task.id)?.intent,
                        hasUnfinishedWork,
                    )
                ) return@forEach

                val partialSize = DownloadTemporaryFiles.recoverableTemporaryFiles(current.task)
                    .firstOrNull { it.isFile }
                    ?.let { file -> runCatching { file.length() }.getOrDefault(0L) }
                    ?.coerceAtLeast(0L)
                    ?: 0L
                val restored = current.copy(
                    status = DownloadStatus.QUEUED,
                    bytesDownloaded = partialSize,
                    totalBytes = current.totalBytes?.coerceAtLeast(partialSize),
                    failureCode = null,
                    failureDetail = null,
                    updatedAtEpochMs = maxOf(
                        System.currentTimeMillis(),
                        current.createdAtEpochMs,
                        current.updatedAtEpochMs,
                    ),
                )
                if (restored != current) {
                    runCatching { store.upsert(restored) }.getOrElse { return@forEach }
                }
                synchronized(controlOperationLock) {
                    if (controlStore.snapshot(restored.task.id) != null) return@synchronized
                    enqueueLocked(restored.task, forceRefresh = false)
                }
            }
        }
    }

    /**
     * WorkManager cancellation alone does not update the app's Room history.
     * Persist the user-requested state only while the exact control generation
     * that requested it is still current.
     */
    private fun persistControlStatus(
        taskId: String,
        target: DownloadStatus,
        expectedIntent: DownloadControlIntentSnapshot,
    ) {
        val store = repository ?: return
        val expectedValue = when (target) {
            DownloadStatus.PAUSED -> "PAUSED"
            DownloadStatus.CANCELLED -> "CANCELLED"
            else -> return
        }
        if (expectedIntent.intent != expectedValue) return

        controlStateScope.launch {
            if (!controlStore.isCurrent(taskId, expectedIntent)) return@launch
            val current = runCatching { store.get(taskId) }.getOrNull() ?: return@launch
            if (!controlStore.isCurrent(taskId, expectedIntent)) return@launch
            if (current.status == DownloadStatus.COMPLETED || current.status == DownloadStatus.FAILED) return@launch
            if (current.status == DownloadStatus.CANCELLED && target == DownloadStatus.PAUSED) return@launch
            runCatching {
                // Re-check immediately before the suspendable persistence operation so an older
                // action cannot clobber a newer resume/cancel/pause action in normal UI races.
                if (!controlStore.isCurrent(taskId, expectedIntent)) return@runCatching
                store.upsert(
                    current.copy(
                        status = target,
                        failureCode = null,
                        failureDetail = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun clearControl(taskId: String) {
        synchronized(controlOperationLock) { controlStore.clearPaused(taskId) }
    }

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
            .putString(
                DownloadWorker.KEY_PROCESSING_MODE,
                (runCatching { task.processingMode }.getOrNull() ?: DownloadProcessingMode.Direct).name,
            )
            .putString(DownloadWorker.KEY_AUDIO_OUTPUT_FORMAT, task.audioOutputFormat?.name)
            .putString(DownloadWorker.KEY_COMPANION_AUDIO_SOURCE_URL, task.companionAudioSourceUrl)
            .putString(DownloadWorker.KEY_COMPANION_AUDIO_SESSION_COOKIE_HOST, task.companionAudioSessionCookieHost)
            .putBoolean(DownloadWorker.KEY_STREAMING_MANIFEST, task.streamingManifest)
            .putBoolean(
                DownloadWorker.KEY_COMPANION_AUDIO_STREAMING_MANIFEST,
                task.companionAudioStreamingManifest,
            )
            .putLong(DownloadWorker.KEY_EXPECTED_BYTES, task.expectedBytes ?: -1L)
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
                task.requestHeaders.entries.firstOrNull { it.key.equals("X-Goog-Visitor-Id", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_X_GOOG_VISITOR_ID, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("X-YouTube-Client-Name", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_YOUTUBE_CLIENT_NAME, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("X-YouTube-Client-Version", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_YOUTUBE_CLIENT_VERSION, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Sec-CH-UA", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_SEC_CH_UA, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Sec-CH-UA-Mobile", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_SEC_CH_UA_MOBILE, it) }
                task.requestHeaders.entries.firstOrNull { it.key.equals("Sec-CH-UA-Platform", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_SEC_CH_UA_PLATFORM, it) }
                task.companionAudioRequestHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_COMPANION_AUDIO_USER_AGENT, it) }
                task.companionAudioRequestHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_COMPANION_AUDIO_REFERER, it) }
                task.companionAudioRequestHeaders.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_COMPANION_AUDIO_ORIGIN, it) }
                task.companionAudioRequestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_COMPANION_AUDIO_ACCEPT, it) }
                task.companionAudioRequestHeaders.entries.firstOrNull { it.key.equals("Accept-Language", ignoreCase = true) }
                    ?.value?.let { putString(DownloadWorker.KEY_COMPANION_AUDIO_ACCEPT_LANGUAGE, it) }
            }
            .build()

    private fun uniqueWorkName(taskId: String): String = "download:$taskId"

    companion object {
        const val TAG = "ahdownload-download"
    }
}
