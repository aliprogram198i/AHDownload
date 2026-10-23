package com.ahdownload.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.app.PendingIntent
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ahdownload.app.R
import com.ahdownload.app.diagnostics.DownloadScopedDiagnosticLogger
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.app.settings.DownloadStoragePolicy
import com.ahdownload.app.settings.DownloadStorageReadiness
import com.ahdownload.app.settings.StorageInfoReader
import com.ahdownload.app.settings.SelectedDirectoryStorage
import com.ahdownload.app.performance.PerformanceLogStore
import com.ahdownload.domain.download.DownloadEngine
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.download.DownloadCoordinator
import com.ahdownload.domain.download.DownloadFailure
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.download.DownloadTemporaryFiles
import com.ahdownload.domain.download.LocalAtomicFileSink
import com.ahdownload.domain.download.OkHttpDownloadByteStream
import com.ahdownload.domain.download.PersistentDownloadQueue
import com.ahdownload.domain.download.StreamingDownloadEngine
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.CandidateRanker
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.feature.home.AndroidYouTubeSessionProvider
import com.ahdownload.feature.home.HomeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationId = id.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
    private var lastNotificationProgressBytes = -1L
    private var lastNotificationProgressAt = 0L
    private var notificationBytesPerSecond = 0L
    private var lowStorageWarningForTask = false

    private val transferCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        DownloadTransferCoordinator(
            applicationContext = applicationContext,
            setForegroundForState = { state -> setForeground(createForegroundInfo(state)) },
            updateNotificationSpeedForState = { state -> updateNotificationSpeed(state) },
            persistAdaptiveRecord = { repository, taskId, state, now ->
                updateAdaptiveRecord(repository, taskId, state, now)
            },
            resolveDynamicHeaders = { url, sessionCookieHost, headers ->
                dynamicHeadersFor(url, sessionCookieHost, headers)
            },
            resolveHost = { url -> hostOf(url) },
        )
    }

    private val destinationPublisher by lazy(LazyThreadSafetyMode.NONE) {
        DownloadDestinationPublisher(applicationContext)
    }

    override suspend fun doWork(): Result {
        val decodedTask = readTask() ?: return Result.failure()
        val audioExtractionRequested = decodedTask.processingMode == DownloadProcessingMode.ExtractAudio
        val muxRequested = decodedTask.processingMode == DownloadProcessingMode.MuxVideoAudio &&
            !decodedTask.companionAudioSourceUrl.isNullOrBlank()

        val extractionSuffix = ".source." + decodedTask.id.take(8) +
            if (decodedTask.streamingManifest) ".mkv" else ""
        val extractionAlreadyStaged =
            audioExtractionRequested && decodedTask.destinationPath.endsWith(extractionSuffix)
        val originalFinalDestinationPath = if (extractionAlreadyStaged) {
            decodedTask.destinationPath.removeSuffix(extractionSuffix)
        } else {
            decodedTask.destinationPath
        }
        val safeFinalDestinationPath = DownloadFileNamePolicy.normalizeDestinationPath(
            originalFinalDestinationPath,
            decodedTask.id,
        )
        val destinationPathNormalized = safeFinalDestinationPath != originalFinalDestinationPath
        val inputTask = decodedTask.copy(
            destinationPath = if (extractionAlreadyStaged) {
                safeFinalDestinationPath + extractionSuffix
            } else {
                safeFinalDestinationPath
            },
        )

        var task = if (extractionAlreadyStaged) {
            inputTask.copy(destinationPath = inputTask.destinationPath.removeSuffix(extractionSuffix))
        } else {
            inputTask
        }

        var sourceTask = when {
            audioExtractionRequested -> task.copy(
                destinationPath = if (extractionAlreadyStaged) {
                    task.destinationPath + extractionSuffix
                } else {
                    extractionSourcePath(task)
                },
                mediaKind = MediaKind.Video,
                processingMode = DownloadProcessingMode.Direct,
            )

            muxRequested -> task.copy(
                destinationPath = muxVideoStagePath(task),
                mediaKind = MediaKind.Video,
                processingMode = DownloadProcessingMode.Direct,
            )

            else -> task
        }

        val companionAudioTask = if (muxRequested) {
            DownloadTask(
                id = task.id + "-audio",
                sourceUrl = task.companionAudioSourceUrl!!,
                destinationPath = muxAudioStagePath(task),
                displayName = task.displayName,
                sessionCookieHost = task.companionAudioSessionCookieHost,
                sourcePageUrl = task.sourcePageUrl,
                mediaKind = MediaKind.Audio,
                processingMode = DownloadProcessingMode.Direct,
                requestHeaders = task.companionAudioRequestHeaders,
                streamingManifest = task.companionAudioStreamingManifest,
            )
        } else {
            null
        }
        setForeground(createForegroundInfo(DownloadState.Preparing))

        val application = applicationContext as com.ahdownload.app.AHDownloadApplication
        val repository = application.downloadRepository
        val diagnostics = DownloadScopedDiagnosticLogger(diagnosticsLogger(), task.id)
        if (destinationPathNormalized) {
            diagnostics.log(
                DiagnosticLevel.WARNING,
                "DOWNLOAD_DESTINATION_NAME_NORMALIZED",
                "تم تقصير اسم ملف قديم ليتوافق مع حدود نظام الملفات قبل إعادة التنزيل.",
                "download.storage.filename",
                mapOf(
                    "task_id" to task.id,
                    "original_name_bytes" to java.io.File(originalFinalDestinationPath)
                        .name.toByteArray(Charsets.UTF_8).size.toString(),
                    "normalized_name_bytes" to java.io.File(safeFinalDestinationPath)
                        .name.toByteArray(Charsets.UTF_8).size.toString(),
                    "staging_location" to "APP_LOCAL_EXTERNAL",
                ),
                null,
            )
        }
        var refreshAttempted = false

        // DownloadLauncher stages bytes in app-specific external storage first, then
        // copies the completed file to an optional user-selected SAF directory. A custom
        // final destination must therefore never disable the staging-volume space check.
        val destinationMode = if (DownloadLocationStore(applicationContext).persistedUri() != null) {
            "CUSTOM_DIRECTORY"
        } else {
            "APP_DEFAULT"
        }
        val storageInfo = runCatching {
            StorageInfoReader.read(applicationContext, task.destinationPath)
        }.getOrNull()
        if (storageInfo == null) {
            diagnostics.log(
                DiagnosticLevel.WARNING,
                "DOWNLOAD_STORAGE_PREFLIGHT_UNAVAILABLE",
                "تعذر قياس مساحة مجلد العمل المحلي؛ سيستمر التنزيل دون افتراض سعة التخزين.",
                "download.storage.preflight",
                mapOf(
                    "destination_mode" to destinationMode,
                    "staging_location" to "APP_LOCAL_EXTERNAL",
                    "expected_bytes" to (task.expectedBytes?.takeIf { it > 0L }?.toString() ?: "unknown"),
                ),
                null,
            )
        } else {
            val expectedBytes = task.expectedBytes?.takeIf { it > 0L }
            val requiredFreeBytes = DownloadStoragePolicy.requiredFreeBytes(expectedBytes)
            val readiness = DownloadStoragePolicy.assess(storageInfo.freeBytes, expectedBytes)
            diagnostics.log(
                DiagnosticLevel.INFO,
                "DOWNLOAD_STORAGE_PREFLIGHT_CHECKED",
                "تم فحص مساحة مجلد العمل المحلي قبل بدء نقل البيانات.",
                "download.storage.preflight",
                mapOf(
                    "destination_mode" to destinationMode,
                    "staging_location" to "APP_LOCAL_EXTERNAL",
                    "free_bytes" to storageInfo.freeBytes.toString(),
                    "expected_bytes" to (expectedBytes?.toString() ?: "unknown"),
                    "required_free_bytes" to requiredFreeBytes.toString(),
                    "storage_readiness" to readiness.name,
                ),
                null,
            )
            when (readiness) {
                DownloadStorageReadiness.READY -> Unit
                DownloadStorageReadiness.LOW_SPACE_WARNING -> {
                    lowStorageWarningForTask = true
                    diagnostics.log(
                        DiagnosticLevel.WARNING,
                        "DOWNLOAD_LOW_STORAGE_WARNING",
                        "المساحة الحرة منخفضة؛ قد تفشل الملفات الكبيرة أو المعالجة اللاحقة.",
                        "download.storage.preflight",
                        mapOf(
                            "destination_mode" to destinationMode,
                            "staging_location" to "APP_LOCAL_EXTERNAL",
                            "free_bytes" to storageInfo.freeBytes.toString(),
                            "expected_bytes" to (expectedBytes?.toString() ?: "unknown"),
                            "required_free_bytes" to requiredFreeBytes.toString(),
                            "minimum_free_bytes" to DownloadStoragePolicy.MINIMUM_FREE_BYTES.toString(),
                        ),
                        null,
                    )
                    setForeground(createForegroundInfo(DownloadState.Preparing))
                }
                DownloadStorageReadiness.INSUFFICIENT_SPACE -> {
                    val detail = "المساحة المتاحة في مجلد العمل المحلي لا تكفي لتنزيل الملف مع الاحتياطي الآمن. وفر مساحة ثم أعد التنزيل."
                    diagnostics.log(
                        DiagnosticLevel.ERROR,
                        "DOWNLOAD_LOW_STORAGE_BLOCKED",
                        "أُوقف التنزيل قبل نقل البيانات لأن المساحة المحلية أقل من الحجم المطلوب مع الاحتياطي الآمن.",
                        "download.storage.preflight",
                        mapOf(
                            "destination_mode" to destinationMode,
                            "staging_location" to "APP_LOCAL_EXTERNAL",
                            "free_bytes" to storageInfo.freeBytes.toString(),
                            "expected_bytes" to (expectedBytes?.toString() ?: "unknown"),
                            "required_free_bytes" to requiredFreeBytes.toString(),
                            "minimum_free_bytes" to DownloadStoragePolicy.MINIMUM_FREE_BYTES.toString(),
                            "task_id" to task.id,
                        ),
                        null,
                    )
                    val existing = repository.get(task.id)
                        ?: com.ahdownload.domain.download.DownloadRecordMapper.queued(inputTask, System.currentTimeMillis())
                    repository.upsert(
                        existing.copy(
                            status = DownloadStatus.FAILED,
                            failureCode = "storage_space_low",
                            failureDetail = detail,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    setForeground(createForegroundInfo(DownloadState.Failed(DownloadFailure.StorageError)))
                    return Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to "storage_space_low",
                            KEY_FAILURE_DETAIL to detail,
                        ),
                    )
                }
            }
        }

        if (shouldRefreshYouTubeTask(sourceTask, repository)) {
            refreshAttempted = true
            val refreshed = refreshYouTubeTask(sourceTask, diagnostics)
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
            if (refreshed.sourceUrl != sourceTask.sourceUrl) {
                try {
                    LocalAtomicFileSink().discard(sourceTask.destinationPath, task.id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    diagnostics.log(
                        DiagnosticLevel.ERROR,
                        "YOUTUBE_RETRY_PARTIAL_RESET_FAILED",
                        "تعذر التخلص من الجزء غير المكتمل قبل تغيير مصدر التنزيل",
                        "download.refresh",
                        mapOf("task_id" to task.id),
                        null,
                    )
                    return Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to "partial_reset_failed",
                            KEY_FAILURE_DETAIL to "تعذر إعادة تهيئة الملف الجزئي لمصدر YouTube الجديد.",
                        ),
                    )
                }
                diagnostics.log(
                    DiagnosticLevel.INFO,
                    "YOUTUBE_RETRY_PARTIAL_RESET",
                    "تم التخلص من الملف الجزئي قبل استخدام مصدر YouTube جديد",
                    "download.refresh",
                    mapOf("task_id" to task.id),
                    null,
                )
            }
            sourceTask = refreshed.copy(
                destinationPath = sourceTask.destinationPath,
                processingMode = DownloadProcessingMode.Direct,
                mediaKind = sourceTask.mediaKind,
                companionAudioSourceUrl = task.companionAudioSourceUrl,
                companionAudioRequestHeaders = task.companionAudioRequestHeaders,
                companionAudioSessionCookieHost = task.companionAudioSessionCookieHost,
            )
            task = if (audioExtractionRequested || muxRequested) {
                task.copy(
                    sourceUrl = sourceTask.sourceUrl,
                    sessionCookieHost = sourceTask.sessionCookieHost,
                    requestHeaders = sourceTask.requestHeaders,
                )
            } else {
                refreshed
            }
            diagnostics.log(
                DiagnosticLevel.INFO,
                "YOUTUBE_RETRY_REFRESH_APPLIED",
                "تم تحديث مصدر YouTube قبل إعادة المحاولة",
                "download.refresh",
                mapOf(
                    "task_id" to task.id,
                    "run_attempt" to runAttemptCount.toString(),
                    "source_host" to hostOf(sourceTask.sourceUrl),
                ),
                null,
            )
        }

        val queue = PersistentDownloadQueue(repository)
        val applicationServices = applicationContext.applicationContext as com.ahdownload.app.AHDownloadApplication
        val engine = PerformanceTrackingDownloadEngine(
            delegate = StreamingDownloadEngine(
                source = OkHttpDownloadByteStream(
                    logger = diagnostics,
                    dynamicHeaders = { url, headers ->
                        dynamicHeadersFor(url, task.sessionCookieHost, headers)
                    },
                ),
                sink = LocalAtomicFileSink(),
                onStorageFailure = { storageStage, error ->
                    val category = StorageFailureDiagnostics.classify(error)
                    diagnostics.log(
                        DiagnosticLevel.ERROR,
                        "DOWNLOAD_STORAGE_IO_FAILED",
                        category,
                        "download.storage.$storageStage",
                        mapOf(
                            "stage" to "DOWNLOAD",
                            "storage_stage" to storageStage,
                            "storage_error_category" to category,
                            "exception_type" to error.javaClass.simpleName,
                            "destination_mode" to destinationMode,
                            "staging_location" to "APP_LOCAL_EXTERNAL",
                        ),
                        null,
                    )
                },
            ),
            performanceLogStore = applicationServices.performanceLogStore,
        )

        val controlStore = DownloadControlStore(applicationContext)

        diagnostics.log(
            DiagnosticLevel.INFO,
            "DOWNLOAD_STARTED",
            "بدء تنفيذ مهمة التنزيل",
            "download.worker",
            mapOf(
                "task_id" to task.id,
                "source_host" to hostOf(task.sourceUrl),
                "destination_mode" to destinationMode,
                "youtube_session_context" to isYouTubeMediaHost(task.sourceUrl).toString(),
                "request_context_present" to task.requestHeaders.keys.sorted().joinToString(",").ifBlank { "none" },
            ),
            null,
        )

        val record = try {
            val reusableAudioSource = if (audioExtractionRequested) {
                java.io.File(sourceTask.destinationPath).takeIf { it.isFile && it.length() > 0L }
            } else {
                null
            }
            if (reusableAudioSource != null) {
                val size = reusableAudioSource.length()
                val existing = queue.enqueue(sourceTask, System.currentTimeMillis())
                val reused = existing.copy(
                    task = task,
                    status = DownloadStatus.PREPARING,
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    failureCode = null,
                    failureDetail = null,
                    destinationUri = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                repository.upsert(reused)
                diagnostics.log(
                    DiagnosticLevel.INFO,
                    "AUDIO_SOURCE_REUSED",
                    "تمت إعادة استخدام المصدر المحلي المكتمل بدل تنزيله مرة أخرى",
                    "download.audio_extraction",
                    mapOf(
                        "task_id" to task.id,
                        "source_bytes" to size.toString(),
                    ),
                    null,
                )
                reused
            } else if (sourceTask.streamingManifest && !muxRequested) {
                transferCoordinator.executeStreamingManifest(
                    task = task,
                    sourceTask = sourceTask,
                    repository = repository,
                    controlStore = controlStore,
                    diagnostics = diagnostics,
                ) ?: return Result.failure(
                    workDataOf(
                        KEY_FAILURE_CODE to "manifest_download_failed",
                        KEY_FAILURE_DETAIL to "تعذر تنزيل مصدر HLS/DASH.",
                    ),
                )
            } else if (muxRequested && companionAudioTask != null) {
                transferCoordinator.executeAdaptiveMux(
                    task = task,
                    videoTask = sourceTask,
                    audioTask = companionAudioTask,
                    engine = engine,
                    repository = repository,
                    controlStore = controlStore,
                    diagnostics = diagnostics,
                ) ?: return Result.failure(
                    workDataOf(
                        KEY_FAILURE_CODE to "mux_failed",
                        KEY_FAILURE_DETAIL to "تعذر تنزيل أو دمج مساري الفيديو والصوت.",
                    ),
                )
            } else {
                val coordinator = DownloadCoordinator(
                    engine = engine,
                    queue = queue,
                    isPauseRequested = { controlStore.isPaused(task.id) },
                    requestedCancellationState = {
                        downloadStateForControlIntent(controlStore.snapshot(task.id)?.intent)
                    },
                    // The outer worker catch owns the generation-checked history update.
                    persistCancellationState = false,
                )
                coordinator.execute(sourceTask) { state ->
                    updateNotificationSpeed(state)
                    setForeground(createForegroundInfo(state))
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            var shouldCleanCancelledArtifacts = false
            // Snapshot the explicit user intent and its generation. A later action may reuse
            // the same intent value (pause -> resume -> pause), so value-only checks are unsafe.
            val intentAtCancellation = controlStore.snapshot(task.id)
            val requestedStatus = downloadStatusForControlIntent(intentAtCancellation?.intent)
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    // Reject this worker's stale callback if any newer user action superseded it.
                    if (
                        requestedStatus != null &&
                        intentAtCancellation != null &&
                        controlStore.isCurrent(task.id, intentAtCancellation)
                    ) {
                        val latest = repository.get(task.id)
                        if (
                            latest?.status != DownloadStatus.COMPLETED &&
                            controlStore.isCurrent(task.id, intentAtCancellation)
                        ) {
                            if (latest != null && latest.status != DownloadStatus.FAILED) {
                                repository.upsert(
                                    latest.copy(
                                        status = requestedStatus,
                                        failureCode = null,
                                        failureDetail = null,
                                        updatedAtEpochMs = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            shouldCleanCancelledArtifacts = requestedStatus == DownloadStatus.CANCELLED
                        }
                    }
                } catch (_: Exception) {
                    // Keep cancellation semantics even if persistence itself fails.
                }

                // Pause and system interruptions preserve resumable bytes; explicit user Cancel does not.
                if (shouldCleanCancelledArtifacts) {
                    cleanupCancelledTemporaryArtifacts(
                        task = task,
                        sourceTask = sourceTask,
                        companionAudioTask = companionAudioTask,
                        diagnostics = diagnostics,
                    )
                }
            }
            diagnostics.log(
                DiagnosticLevel.INFO,
                "DOWNLOAD_WORK_CANCELLED",
                when (requestedStatus) {
                    DownloadStatus.PAUSED -> "تم إيقاف التنزيل مؤقتًا"
                    DownloadStatus.CANCELLED -> "تم إلغاء مهمة التنزيل"
                    else -> "توقف العامل دون طلب إيقاف مؤقت أو إلغاء مؤكد"
                },
                "download.worker",
                mapOf(
                    "task_id" to task.id,
                    "control_intent" to (intentAtCancellation?.intent ?: "UNSPECIFIED"),
                    "persisted_status" to (requestedStatus?.name ?: "UNCHANGED"),
                ),
                cancelled,
            )
            throw cancelled
        }

        var completedRecord = record
        val stagedAudioSource = if (audioExtractionRequested) {
            java.io.File(sourceTask.destinationPath).takeIf { it.isFile && it.length() > 0L }
        } else {
            null
        }
        if (audioExtractionRequested && (record.status == DownloadStatus.COMPLETED || stagedAudioSource != null)) {
            // The source transfer is complete, but the user-visible audio output is not.
            // Do not expose this task as completed while extraction is still running.
            completedRecord = record.copy(
                task = task,
                status = DownloadStatus.PREPARING,
                bytesDownloaded = 0L,
                totalBytes = null,
                failureCode = null,
                failureDetail = null,
                destinationUri = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            repository.upsert(completedRecord)
            setForeground(createForegroundInfo(DownloadState.Preparing))
            val audioOutputFormat = task.audioOutputFormat ?: AudioOutputFormat.M4a
            diagnostics.log(
                DiagnosticLevel.INFO,
                "AUDIO_EXTRACTION_STARTED",
                "اكتمل تنزيل المصدر وبدأ استخراج الصوت ثم تحويله",
                "download.audio_extraction",
                mapOf(
                    "task_id" to task.id,
                    "processing_mode" to task.processingMode.name,
                    "output_format" to audioOutputFormat.name,
                    "source_file_present" to java.io.File(sourceTask.destinationPath).isFile.toString(),
                ),
                null,
            )

            val outputFile = java.io.File(task.destinationPath)
            val fastCopyFile = java.io.File(outputFile.parentFile, ".${outputFile.name}.fastcopy")
            val extraction: kotlin.Result<java.io.File> = try {
                AudioTranscoder()
                    .transcode(
                        inputFile = java.io.File(sourceTask.destinationPath),
                        outputFile = outputFile,
                        outputFormat = audioOutputFormat,
                    )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                var shouldCleanCancelledArtifacts = false
                val intentAtCancellation = controlStore.snapshot(task.id)
                val requestedStatus = downloadStatusForControlIntent(intentAtCancellation?.intent)
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (
                            requestedStatus != null &&
                            intentAtCancellation != null &&
                            controlStore.isCurrent(task.id, intentAtCancellation)
                        ) {
                            val latest = repository.get(task.id)
                            if (
                                latest != null &&
                                latest.status != DownloadStatus.COMPLETED &&
                                latest.status != DownloadStatus.FAILED &&
                                controlStore.isCurrent(task.id, intentAtCancellation)
                            ) {
                                repository.upsert(
                                    latest.copy(
                                        status = requestedStatus,
                                        bytesDownloaded = 0L,
                                        totalBytes = null,
                                        failureCode = null,
                                        failureDetail = null,
                                        updatedAtEpochMs = System.currentTimeMillis(),
                                    ),
                                )
                                shouldCleanCancelledArtifacts = requestedStatus == DownloadStatus.CANCELLED
                            }
                        }
                    } catch (_: Exception) {
                        // Preserve cancellation even if durable state cannot be updated.
                    }

                    // The staged source is reusable after pause/system interruption; output
                    // generated by an interrupted FFmpeg session is never treated as complete.
                    outputFile.delete()
                    fastCopyFile.delete()
                    if (shouldCleanCancelledArtifacts) {
                        cleanupCancelledTemporaryArtifacts(
                            task = task,
                            sourceTask = sourceTask,
                            companionAudioTask = companionAudioTask,
                            diagnostics = diagnostics,
                        )
                    }
                }
                diagnostics.log(
                    DiagnosticLevel.INFO,
                    "AUDIO_EXTRACTION_CANCELLED",
                    when (requestedStatus) {
                        DownloadStatus.PAUSED -> "تم إيقاف استخراج الصوت مؤقتًا"
                        DownloadStatus.CANCELLED -> "تم إلغاء استخراج الصوت"
                        else -> "توقف استخراج الصوت بسبب دورة حياة العامل"
                    },
                    "download.audio_extraction",
                    mapOf(
                        "task_id" to task.id,
                        "control_intent" to (intentAtCancellation?.intent ?: "UNSPECIFIED"),
                        "persisted_status" to (requestedStatus?.name ?: "UNCHANGED"),
                    ),
                    cancelled,
                )
                throw cancelled
            } catch (error: Throwable) {
                kotlin.Result.failure(error)
            }

            if (extraction.isFailure) {
                outputFile.delete()
                fastCopyFile.delete()
                val error = extraction.exceptionOrNull()
                val detail = error?.message?.takeIf { it.isNotBlank() } ?: "تعذر استخراج مسار صوت صالح من المصدر."
                diagnostics.log(
                    DiagnosticLevel.ERROR,
                    "AUDIO_EXTRACTION_FAILED",
                    "فشل استخراج الصوت من الوسيط الذي تم تنزيله",
                    "download.audio_extraction",
                    mapOf(
                        "task_id" to task.id,
                        "processing_mode" to task.processingMode.name,
                        "output_format" to audioOutputFormat.name,
                        "source_file_present" to java.io.File(sourceTask.destinationPath).isFile.toString(),
                        "output_file_present" to outputFile.isFile.toString(),
                    ),
                    error,
                )
                completedRecord = record.copy(
                    task = task,
                    status = DownloadStatus.FAILED,
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    failureCode = "audio_extraction_error",
                    failureDetail = detail,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                repository.upsert(completedRecord)
                return Result.failure(
                    workDataOf(
                        KEY_FAILURE_CODE to "audio_extraction_error",
                        KEY_FAILURE_DETAIL to detail,
                    ),
                )
            }

            runCatching { java.io.File(sourceTask.destinationPath).delete() }
            val finalAudioBytes = outputFile.length().coerceAtLeast(0L)
            completedRecord = record.copy(
                task = task,
                status = DownloadStatus.COMPLETED,
                bytesDownloaded = finalAudioBytes,
                totalBytes = finalAudioBytes,
                failureCode = null,
                failureDetail = null,
                destinationUri = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            diagnostics.log(
                DiagnosticLevel.INFO,
                "AUDIO_EXTRACTION_COMPLETED",
                "تم استخراج الصوت فقط بنجاح",
                "download.audio_extraction",
                mapOf(
                    "task_id" to task.id,
                    "output_format" to audioOutputFormat.name,
                    "output_file_present" to java.io.File(task.destinationPath).isFile.toString(),
                    "output_size_bytes" to java.io.File(task.destinationPath).length().toString(),
                ),
                null,
            )
        }

        if (completedRecord.status == DownloadStatus.COMPLETED) {
            when (val publishOutcome = destinationPublisher.publish(completedRecord, task, diagnostics)) {
                is DownloadDestinationPublishOutcome.Published -> completedRecord = publishOutcome.record
                is DownloadDestinationPublishOutcome.Failed -> return Result.failure(
                    workDataOf(
                        KEY_FAILURE_CODE to "destination_storage_error",
                        KEY_FAILURE_DETAIL to publishOutcome.detail,
                    ),
                )
            }
            if (completedRecord !== record) {
                repository.upsert(completedRecord)
            }
        }

        if (completedRecord.status == DownloadStatus.FAILED) {
            diagnostics.log(
                level = DiagnosticLevel.ERROR,
                type = "DOWNLOAD",
                reason = record.failureCode ?: "FAILED",
                operation = "download.worker",
                context = buildMap {
                    put("task_id", task.id)
                    record.failureCode?.let { put("failure_code", it) }
                    record.failureDetail?.let { put("failure_detail", it) }
                },
                throwable = null,
            )
        } else if (completedRecord.status == DownloadStatus.CANCELLED) {
            diagnostics.log(
                level = DiagnosticLevel.WARNING,
                type = "DOWNLOAD",
                reason = "CANCELLED",
                operation = "download.worker",
                context = mapOf("task_id" to task.id),
                throwable = null,
            )
        }

        return when (completedRecord.status) {
            DownloadStatus.COMPLETED -> Result.success()
            DownloadStatus.FAILED -> {
                val retryableYouTube403 =
                    !refreshAttempted &&
                        runAttemptCount == 0 &&
                        isYouTubeTask(task) &&
                        completedRecord.failureCode == "http_error" &&
                        completedRecord.failureDetail == "403"

                if (
                    completedRecord.failureCode == "network_error" ||
                    completedRecord.failureCode == "incomplete_response" && runAttemptCount < MAX_INCOMPLETE_RESPONSE_RETRIES ||
                    completedRecord.failureCode == "http_error" && DownloadRetryPolicy.isRetryableHttp(completedRecord.failureDetail) ||
                    retryableYouTube403
                ) {
                    Result.retry()
                } else {
                    Result.failure(
                        workDataOf(
                            KEY_FAILURE_CODE to completedRecord.failureCode,
                            KEY_FAILURE_DETAIL to completedRecord.failureDetail,
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

    private suspend fun cleanupCancelledTemporaryArtifacts(
        task: DownloadTask,
        sourceTask: DownloadTask,
        companionAudioTask: DownloadTask?,
        diagnostics: DiagnosticLogger,
    ) {
        val sink = LocalAtomicFileSink()
        val transferPaths = buildList {
            add(sourceTask.destinationPath to task.id)
            companionAudioTask?.let { add(it.destinationPath to it.id) }
        }
        var removedPartialCount = 0
        var cleanupFailureCount = 0
        for ((path, transferId) in transferPaths) {
            try {
                val before = DownloadTemporaryFiles.temporaryFile(path, transferId).exists() ||
                    DownloadTemporaryFiles.legacyTemporaryFile(path).exists()
                sink.discard(path, transferId)
                if (before) removedPartialCount++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                cleanupFailureCount++
            }
        }

        val intermediateFiles = buildList {
            if (task.processingMode == DownloadProcessingMode.ExtractAudio &&
                sourceTask.destinationPath != task.destinationPath
            ) {
                add(java.io.File(sourceTask.destinationPath))
            }
            if (task.processingMode == DownloadProcessingMode.MuxVideoAudio) {
                add(java.io.File(sourceTask.destinationPath))
                companionAudioTask?.let { add(java.io.File(it.destinationPath)) }
                add(muxTemporaryOutputFile(java.io.File(task.destinationPath)))
            }
            listOfNotNull(sourceTask, companionAudioTask)
                .filter { it.streamingManifest }
                .forEach { staged ->
                    val output = java.io.File(staged.destinationPath)
                    add(java.io.File(output.parentFile, ".${output.name}.manifesting.mkv"))
                }
        }.distinctBy { it.absolutePath }

        var removedIntermediateCount = 0
        for (file in intermediateFiles) {
            if (file.isFile) {
                if (file.delete()) removedIntermediateCount++ else cleanupFailureCount++
            }
        }

        diagnostics.log(
            if (cleanupFailureCount == 0) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "DOWNLOAD_CANCEL_ARTIFACT_CLEANUP",
            if (cleanupFailureCount == 0) "تم تنظيف الملفات الجزئية الخاصة بالمهمة الملغاة"
            else "أُلغيت المهمة مع تعذر تنظيف بعض الملفات الجزئية",
            "download.cleanup",
            mapOf(
                "task_id" to task.id,
                "partial_files_removed" to removedPartialCount.toString(),
                "intermediate_files_removed" to removedIntermediateCount.toString(),
                "cleanup_failures" to cleanupFailureCount.toString(),
            ),
            null,
        )
    }

    private fun diagnosticsLogger(): com.ahdownload.app.diagnostics.PersistentDiagnosticLogger =
        (applicationContext as com.ahdownload.app.AHDownloadApplication).diagnosticLogger

    private fun dynamicHeadersFor(
        url: String,
        sessionCookieHost: String?,
        requestHeaders: Map<String, String>,
    ): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        val host = hostOf(url)
        return buildMap {
            when {
                isYouTubeMediaHost(url) -> {
                    if (requestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                        put("Referer", "https://www.youtube.com/")
                    }
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
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        val contentFingerprint = inputData.getString(KEY_CONTENT_FINGERPRINT).orEmpty()
        val sessionCookieHost = inputData.getString(KEY_SESSION_COOKIE_HOST)
        val sourcePageUrl = inputData.getString(KEY_SOURCE_PAGE_URL)
        val mediaKind = inputData.getString(KEY_MEDIA_KIND)
            ?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
        val companionAudioSourceUrl = inputData.getString(KEY_COMPANION_AUDIO_SOURCE_URL)
        val companionAudioSessionCookieHost = inputData.getString(KEY_COMPANION_AUDIO_SESSION_COOKIE_HOST)
        val streamingManifest = inputData.getBoolean(KEY_STREAMING_MANIFEST, false)
        val companionAudioStreamingManifest =
            inputData.getBoolean(KEY_COMPANION_AUDIO_STREAMING_MANIFEST, false)
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, -1L).takeIf { it > 0L }
        val companionAudioRequestHeaders = buildMap {
            inputData.getString(KEY_COMPANION_AUDIO_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            inputData.getString(KEY_COMPANION_AUDIO_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            inputData.getString(KEY_COMPANION_AUDIO_ORIGIN)?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
            inputData.getString(KEY_COMPANION_AUDIO_ACCEPT)?.takeIf { it.isNotBlank() }?.let { put("Accept", it) }
            inputData.getString(KEY_COMPANION_AUDIO_ACCEPT_LANGUAGE)?.takeIf { it.isNotBlank() }?.let { put("Accept-Language", it) }
        }
        val processingMode = inputData.getString(KEY_PROCESSING_MODE)
            ?.let { runCatching { DownloadProcessingMode.valueOf(it) }.getOrNull() }
            ?: DownloadProcessingMode.Direct
        val audioOutputFormat = inputData.getString(KEY_AUDIO_OUTPUT_FORMAT)
            ?.let { runCatching { AudioOutputFormat.valueOf(it) }.getOrNull() }
        val requestHeaders = buildMap {
            inputData.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            inputData.getString(KEY_REFERER)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            inputData.getString(KEY_ORIGIN)?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
            inputData.getString(KEY_ACCEPT)?.takeIf { it.isNotBlank() }?.let { put("Accept", it) }
            inputData.getString(KEY_ACCEPT_LANGUAGE)?.takeIf { it.isNotBlank() }?.let { put("Accept-Language", it) }
            inputData.getString(KEY_SEC_FETCH_DEST)?.takeIf { it.isNotBlank() }?.let { put("Sec-Fetch-Dest", it) }
            inputData.getString(KEY_SEC_FETCH_MODE)?.takeIf { it.isNotBlank() }?.let { put("Sec-Fetch-Mode", it) }
            inputData.getString(KEY_SEC_FETCH_SITE)?.takeIf { it.isNotBlank() }?.let { put("Sec-Fetch-Site", it) }
            inputData.getString(KEY_X_GOOG_VISITOR_ID)?.takeIf { it.isNotBlank() }?.let { put("X-Goog-Visitor-Id", it) }
            inputData.getString(KEY_YOUTUBE_CLIENT_NAME)?.takeIf { it.isNotBlank() }?.let { put("X-YouTube-Client-Name", it) }
            inputData.getString(KEY_YOUTUBE_CLIENT_VERSION)?.takeIf { it.isNotBlank() }?.let { put("X-YouTube-Client-Version", it) }
            inputData.getString(KEY_SEC_CH_UA)?.takeIf { it.isNotBlank() }?.let { put("Sec-CH-UA", it) }
            inputData.getString(KEY_SEC_CH_UA_MOBILE)?.takeIf { it.isNotBlank() }?.let { put("Sec-CH-UA-Mobile", it) }
            inputData.getString(KEY_SEC_CH_UA_PLATFORM)?.takeIf { it.isNotBlank() }?.let { put("Sec-CH-UA-Platform", it) }
        }

        return DownloadTask(
            id = taskId,
            sourceUrl = sourceUrl,
            destinationPath = destinationPath,
            displayName = displayName,
            thumbnailUrl = thumbnailUrl,
            contentFingerprint = contentFingerprint,
            sessionCookieHost = sessionCookieHost,
            sourcePageUrl = sourcePageUrl,
            mediaKind = mediaKind,
            processingMode = processingMode,
            audioOutputFormat = audioOutputFormat,
            companionAudioSourceUrl = companionAudioSourceUrl,
            companionAudioRequestHeaders = companionAudioRequestHeaders,
            companionAudioSessionCookieHost = companionAudioSessionCookieHost,
            streamingManifest = streamingManifest,
            companionAudioStreamingManifest = companionAudioStreamingManifest,
            expectedBytes = expectedBytes,
            requestHeaders = requestHeaders,
        )
    }

    private suspend fun updateAdaptiveRecord(
        repository: com.ahdownload.domain.download.DownloadRepository,
        taskId: String,
        state: DownloadState,
        now: Long,
    ) {
        val current = repository.get(taskId) ?: return
        val mapped = com.ahdownload.domain.download.DownloadRecordMapper.fromState(
            current,
            state,
            now,
        )
        repository.upsert(mapped)
    }

    private fun muxVideoStagePath(task: DownloadTask): String =
        task.destinationPath + ".video." + task.id.take(8)

    private fun muxAudioStagePath(task: DownloadTask): String =
        task.destinationPath + ".audio." + task.id.take(8)

    private fun updateNotificationSpeed(state: DownloadState) {
        if (state !is DownloadState.Downloading) return
        val now = System.currentTimeMillis()
        if (lastNotificationProgressAt > 0L) {
            val elapsed = now - lastNotificationProgressAt
            val delta = state.bytesDownloaded - lastNotificationProgressBytes
            if (elapsed >= 500L && delta >= 0L) {
                notificationBytesPerSecond = delta * 1000L / elapsed
                lastNotificationProgressBytes = state.bytesDownloaded
                lastNotificationProgressAt = now
                return
            }
        }
        lastNotificationProgressBytes = state.bytesDownloaded
        lastNotificationProgressAt = now
    }

    private fun createForegroundInfo(state: DownloadState): ForegroundInfo {
        ensureNotificationChannel()

        val openIntent = Intent(applicationContext, com.ahdownload.app.MainActivity::class.java).apply {
            putExtra(com.ahdownload.app.MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ahdownload_notification)
            .setColor(0xFFFF791A.toInt())
            .setContentTitle("AHDownload")
            .setContentText(state.toNotificationText(lowStorageWarningForTask, notificationBytesPerSecond))
            .setOngoing(
                state !is DownloadState.Completed &&
                    state !is DownloadState.Failed &&
                    state !is DownloadState.Cancelled &&
                    state !is DownloadState.Paused,
            )
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
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

    private fun extractionSourcePath(task: DownloadTask): String =
        task.destinationPath + ".source." + task.id.take(8) +
            if (task.streamingManifest) ".mkv" else ""

    private suspend fun shouldRefreshYouTubeTask(
        task: DownloadTask,
        repository: com.ahdownload.domain.download.DownloadRepository,
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
        diagnostics: DiagnosticLogger,
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

        val resolved = resolver.resolve(
            link,
            operationId = id.toString(),
            forceYouTubeFallbacks = true,
        )
        if (resolved !is ResolverResult.Success) return null

        val candidates = if (task.processingMode == DownloadProcessingMode.ExtractAudio) {
            val directAudio = CandidateRanker()
                .rank(
                    resolved.candidates.filter {
                        it.format.kind == MediaKind.Audio && it.format.hasAudio
                    },
                    requestedKind = MediaKind.Audio,
                )
                .sortedWith(
                    compareBy<MediaCandidate> { DownloadRetryPolicy.youtubeRefreshPriority(it) }
                        .thenByDescending { it.format.bitrateKbps ?: 0 }
                        .thenBy { it.id },
                )
                .take(6)
            val muxedVideo = CandidateRanker()
                .rank(
                    resolved.candidates.filter {
                        it.format.kind == MediaKind.Video &&
                            it.format.hasVideo &&
                            it.format.hasAudio
                    },
                    requestedKind = MediaKind.Video,
                )
                .sortedWith(
                    compareBy<MediaCandidate> { DownloadRetryPolicy.youtubeRefreshPriority(it) }
                        .thenByDescending { it.format.height ?: 0 }
                        .thenByDescending { it.format.bitrateKbps ?: 0 }
                        .thenBy { it.id },
                )
                .take(4)
            DownloadRetryPolicy.interleaveYouTubeRefreshCandidates(
                primary = directAudio,
                fallback = muxedVideo,
            )
        } else {
            CandidateRanker()
                .rank(
                    resolved.candidates.filter {
                        it.format.kind == expectedKind &&
                            when (it.format.kind) {
                                MediaKind.Video -> it.format.hasVideo && it.format.hasAudio
                                MediaKind.Audio -> it.format.hasAudio
                                else -> false
                            }
                    },
                    requestedKind = expectedKind,
                )
                .sortedWith(
                    compareBy<MediaCandidate> { DownloadRetryPolicy.youtubeRefreshPriority(it) }
                        .thenByDescending { it.format.height ?: 0 }
                        .thenByDescending { it.format.bitrateKbps ?: 0 }
                        .thenBy { it.id },
                )
                .take(4)
        }

        for (candidate in candidates) {
            val validation = resolver.validate(candidate, operationId = id.toString())
            if (validation is com.ahdownload.domain.validation.CandidateValidationResult.Valid) {
                return task.copy(
                    sourceUrl = validation.finalUrl,
                    sourcePageUrl = link.normalizedUrl,
                    mediaKind = task.mediaKind,
                    processingMode = task.processingMode,
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
                                key.equals("Sec-Fetch-Site", ignoreCase = true) ||
                            key.equals("X-Goog-Visitor-Id", ignoreCase = true) ||
                            key.equals("X-YouTube-Client-Name", ignoreCase = true) ||
                            key.equals("X-YouTube-Client-Version", ignoreCase = true) ||
                            key.equals("Sec-CH-UA", ignoreCase = true) ||
                            key.equals("Sec-CH-UA-Mobile", ignoreCase = true) ||
                            key.equals("Sec-CH-UA-Platform", ignoreCase = true) )
                    },
                )
            }
        }

        return null
    }

    companion object {
        private const val MAX_INCOMPLETE_RESPONSE_RETRIES = 2

        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_DESTINATION_PATH = "destination_path"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_CONTENT_FINGERPRINT = "content_fingerprint"
        const val KEY_SESSION_COOKIE_HOST = "session_cookie_host"
        const val KEY_SOURCE_PAGE_URL = "source_page_url"
        const val KEY_MEDIA_KIND = "media_kind"
        const val KEY_PROCESSING_MODE = "processing_mode"
        const val KEY_AUDIO_OUTPUT_FORMAT = "audio_output_format"
        const val KEY_COMPANION_AUDIO_SOURCE_URL = "companion_audio_source_url"
        const val KEY_COMPANION_AUDIO_SESSION_COOKIE_HOST = "companion_audio_session_cookie_host"
        const val KEY_COMPANION_AUDIO_STREAMING_MANIFEST = "companion_audio_streaming_manifest"
        const val KEY_EXPECTED_BYTES = "expected_bytes"
        const val KEY_STREAMING_MANIFEST = "streaming_manifest"
        const val KEY_COMPANION_AUDIO_USER_AGENT = "companion_audio_user_agent"
        const val KEY_COMPANION_AUDIO_REFERER = "companion_audio_referer"
        const val KEY_COMPANION_AUDIO_ORIGIN = "companion_audio_origin"
        const val KEY_COMPANION_AUDIO_ACCEPT = "companion_audio_accept"
        const val KEY_COMPANION_AUDIO_ACCEPT_LANGUAGE = "companion_audio_accept_language"
        const val KEY_FORCE_REFRESH = "force_refresh"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_REFERER = "referer"
        const val KEY_ORIGIN = "origin"
        const val KEY_ACCEPT = "accept"
        const val KEY_ACCEPT_LANGUAGE = "accept_language"
        const val KEY_SEC_FETCH_DEST = "sec_fetch_dest"
        const val KEY_SEC_FETCH_MODE = "sec_fetch_mode"
        const val KEY_SEC_FETCH_SITE = "sec_fetch_site"
        const val KEY_X_GOOG_VISITOR_ID = "x_goog_visitor_id"
        const val KEY_YOUTUBE_CLIENT_NAME = "youtube_client_name"
        const val KEY_YOUTUBE_CLIENT_VERSION = "youtube_client_version"
        const val KEY_SEC_CH_UA = "sec_ch_ua"
        const val KEY_SEC_CH_UA_MOBILE = "sec_ch_ua_mobile"
        const val KEY_SEC_CH_UA_PLATFORM = "sec_ch_ua_platform"
        const val KEY_FAILURE_CODE = "failure_code"
        const val KEY_FAILURE_DETAIL = "failure_detail"
        const val TAG = "ahdownload-download-worker"

        private const val CHANNEL_ID = "ahdownload_downloads"
    }
}
