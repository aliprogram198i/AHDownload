package com.ahdownload.app.download

import android.content.Context
import android.webkit.CookieManager
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.download.DownloadEngine
import com.ahdownload.domain.download.DownloadFailure
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.download.LocalAtomicFileSink
import com.ahdownload.domain.download.OkHttpDownloadByteStream
import com.ahdownload.domain.download.PersistentDownloadQueue
import com.ahdownload.domain.download.StreamingDownloadEngine
import kotlinx.coroutines.CancellationException

/**
 * Coordinates the actual media transfer and video/audio muxing pipeline.
 *
 * WorkManager lifecycle, notification construction, and durable state updates remain
 * owned by DownloadWorker and are injected through callbacks. This preserves the
 * single user-visible task while keeping staged media files as internal details.
 */
internal class DownloadTransferCoordinator(
    private val applicationContext: Context,
    private val setForegroundForState: suspend (DownloadState) -> Unit,
    private val updateNotificationSpeedForState: (DownloadState) -> Unit,
    private val persistAdaptiveRecord: suspend (
        com.ahdownload.domain.download.DownloadRepository,
        String,
        DownloadState,
        Long,
    ) -> Unit,
    private val resolveDynamicHeaders: (String, String?, Map<String, String>) -> Map<String, String>,
    private val resolveHost: (String) -> String,
) {
    suspend fun executeStreamingManifest(
        task: DownloadTask,
        sourceTask: DownloadTask,
        repository: com.ahdownload.domain.download.DownloadRepository,
        controlStore: DownloadControlStore,
        diagnostics: DiagnosticLogger,
    ): com.ahdownload.domain.download.DownloadRecord? {
        val queue = PersistentDownloadQueue(repository)
        queue.enqueue(task, System.currentTimeMillis())
        persistAdaptiveRecord(
            repository,
            task.id,
            DownloadState.Downloading(0L, null),
            System.currentTimeMillis(),
        )
        setForegroundForState(DownloadState.Downloading(0L, null))

        return try {
            val cookie = CookieManager.getInstance()
                .getCookie(sourceTask.sourceUrl)
                ?.takeIf { it.isNotBlank() }

            val performanceStore = (applicationContext.applicationContext as com.ahdownload.app.AHDownloadApplication).performanceLogStore
            val performanceSession = performanceStore.beginDownload(sourceTask)
            val result = try {
                FfmpegManifestDownloader().download(
                    sourceUrl = sourceTask.sourceUrl,
                    outputFile = java.io.File(sourceTask.destinationPath),
                    requestHeaders = sourceTask.requestHeaders,
                    cookie = cookie,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                performanceStore.finishDownload(performanceSession, "CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                performanceStore.finishDownload(performanceSession, "FAILED")
                throw error
            }
            if (result.isSuccess) {
                val outputFile = result.getOrNull()?.takeIf { it.isFile }
                performanceStore.finishDownload(
                    session = performanceSession,
                    outcome = "COMPLETED",
                    bytesDownloadedOverride = outputFile?.length(),
                    totalBytesOverride = outputFile?.length(),
                )
            } else {
                performanceStore.finishDownload(performanceSession, "FAILED_MANIFEST")
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                diagnostics.log(
                    DiagnosticLevel.ERROR,
                    "STREAMING_MANIFEST_DOWNLOAD_FAILED",
                    error?.message ?: "manifest_download_failed",
                    "download.manifest",
                    mapOf(
                        "task_id" to task.id,
                        "source_host" to resolveHost(sourceTask.sourceUrl),
                        "manifest_type" to if (sourceTask.sourceUrl.contains(".mpd", true)) "DASH" else "HLS",
                    ),
                    error,
                )
                val failedRecord = queue.applyState(
                    task.id,
                    DownloadState.Failed(DownloadFailure.InvalidResponse),
                    System.currentTimeMillis(),
                )
                val safeDetail = error?.message
                    ?.replace('\n', ' ')
                    ?.take(1000)
                    ?.takeIf { it.isNotBlank() }
                val enrichedFailure = failedRecord.copy(
                    failureDetail = safeDetail ?: failedRecord.failureDetail
                        ?: "FFmpeg could not acquire the HLS/DASH media source.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                repository.upsert(enrichedFailure)
                return enrichedFailure
            }

            val output = result.getOrThrow()
            persistAdaptiveRecord(
                repository,
                task.id,
                DownloadState.Downloading(output.length(), output.length()),
                System.currentTimeMillis(),
            )
            queue.applyState(task.id, DownloadState.Completed, System.currentTimeMillis())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The enclosing worker handler alone persists explicit, current user intent.
            throw cancelled
        } catch (error: Throwable) {
            diagnostics.log(
                DiagnosticLevel.ERROR,
                "STREAMING_MANIFEST_UNEXPECTED",
                error.message ?: error::class.simpleName.orEmpty(),
                "download.manifest",
                mapOf("task_id" to task.id, "source_host" to resolveHost(sourceTask.sourceUrl)),
                error,
            )
            queue.applyState(
                task.id,
                DownloadState.Failed(DownloadFailure.NetworkError),
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun executeAdaptiveMux(
        task: DownloadTask,
        videoTask: DownloadTask,
        audioTask: DownloadTask,
        engine: DownloadEngine,
        repository: com.ahdownload.domain.download.DownloadRepository,
        controlStore: DownloadControlStore,
        diagnostics: DiagnosticLogger,
    ): com.ahdownload.domain.download.DownloadRecord? {
        val queue = PersistentDownloadQueue(repository)
        queue.enqueue(task, System.currentTimeMillis())

        fun update(state: DownloadState) {
            // State persistence is intentionally tied to the main user-visible task.
            // The staged video/audio files remain private implementation details.
        }

        val videoFile = java.io.File(videoTask.destinationPath)
        val audioFile = java.io.File(audioTask.destinationPath)
        val outputFile = java.io.File(task.destinationPath)

        try {
            if (!videoFile.isFile || videoFile.length() <= 0L) {
                val videoFinal = if (videoTask.streamingManifest) {
                    setForegroundForState(DownloadState.Preparing)
                    val manifestResult = FfmpegManifestDownloader().download(
                        sourceUrl = videoTask.sourceUrl,
                        outputFile = videoFile,
                        requestHeaders = videoTask.requestHeaders,
                        cookie = CookieManager.getInstance()
                            .getCookie(videoTask.sourceUrl)
                            ?.takeIf { it.isNotBlank() },
                    )
                    if (manifestResult.isSuccess && videoFile.isFile && videoFile.length() > 0L) {
                        val size = videoFile.length()
                        val stagedState = DownloadState.Downloading(size, size)
                        persistAdaptiveRecord(repository, task.id, stagedState, System.currentTimeMillis())
                        setForegroundForState(stagedState)
                        DownloadState.Completed
                    } else {
                        val error = manifestResult.exceptionOrNull()
                            ?: IllegalStateException("Manifest staging produced no media file")
                        diagnostics.log(
                            DiagnosticLevel.ERROR,
                            "ADAPTIVE_VIDEO_MANIFEST_FAILED",
                            "تعذر تجهيز مصدر الفيديو HLS/DASH قبل دمج الصوت",
                            "download.mux.video_manifest",
                            mapOf(
                                "task_id" to task.id,
                                "source_host" to resolveHost(videoTask.sourceUrl),
                            ),
                            error,
                        )
                        persistAdaptiveRecord(
                            repository,
                            task.id,
                            DownloadState.Failed(DownloadFailure.NetworkError),
                            System.currentTimeMillis(),
                        )
                        DownloadState.Failed(DownloadFailure.NetworkError)
                    }
                } else {
                    var directVideoFinal: DownloadState = DownloadState.Preparing
                    engine.download(videoTask) { state ->
                        directVideoFinal = state
                        persistAdaptiveRecord(repository, task.id, state, System.currentTimeMillis())
                        updateNotificationSpeedForState(state)
                        setForegroundForState(state)
                    }
                    directVideoFinal
                }
                if (videoFinal !is DownloadState.Completed) return null
            }

            if (!audioFile.isFile || audioFile.length() <= 0L) {
                val audioFinal = if (audioTask.streamingManifest) {
                    setForegroundForState(DownloadState.Preparing)
                    val manifestResult = FfmpegManifestDownloader().download(
                        sourceUrl = audioTask.sourceUrl,
                        outputFile = audioFile,
                        requestHeaders = audioTask.requestHeaders,
                        cookie = CookieManager.getInstance()
                            .getCookie(audioTask.sourceUrl)
                            ?.takeIf { it.isNotBlank() },
                    )
                    if (manifestResult.isSuccess && audioFile.isFile && audioFile.length() > 0L) {
                        val size = audioFile.length()
                        val stagedState = DownloadState.Downloading(size, size)
                        persistAdaptiveRecord(repository, task.id, stagedState, System.currentTimeMillis())
                        setForegroundForState(stagedState)
                        DownloadState.Completed
                    } else {
                        val error = manifestResult.exceptionOrNull()
                            ?: IllegalStateException("Companion audio manifest produced no media file")
                        diagnostics.log(
                            DiagnosticLevel.ERROR,
                            "ADAPTIVE_AUDIO_MANIFEST_FAILED",
                            "تعذر تجهيز مصدر الصوت HLS/DASH قبل دمج المسارين",
                            "download.mux.audio_manifest",
                            mapOf(
                                "task_id" to task.id,
                                "source_host" to resolveHost(audioTask.sourceUrl),
                            ),
                            error,
                        )
                        persistAdaptiveRecord(
                            repository,
                            task.id,
                            DownloadState.Failed(DownloadFailure.NetworkError),
                            System.currentTimeMillis(),
                        )
                        DownloadState.Failed(DownloadFailure.NetworkError)
                    }
                } else {
                    var directAudioFinal: DownloadState = DownloadState.Preparing
                    val audioEngine = StreamingDownloadEngine(
                        source = OkHttpDownloadByteStream(
                            logger = diagnostics,
                            dynamicHeaders = { url, headers ->
                                resolveDynamicHeaders(url, task.companionAudioSessionCookieHost, headers)
                            },
                        ),
                        sink = LocalAtomicFileSink(),
                    )
                    audioEngine.download(audioTask) { state ->
                        directAudioFinal = state
                        val mapped = when (state) {
                            is DownloadState.Downloading -> DownloadState.Downloading(
                                bytesDownloaded = state.bytesDownloaded.coerceAtLeast(0L),
                                totalBytes = state.totalBytes,
                            )
                            DownloadState.Completed -> DownloadState.Downloading(
                                bytesDownloaded = audioFile.length(),
                                totalBytes = audioFile.length(),
                            )
                            else -> state
                        }
                        persistAdaptiveRecord(repository, task.id, mapped, System.currentTimeMillis())
                        setForegroundForState(mapped)
                    }
                    directAudioFinal
                }
                if (audioFinal !is DownloadState.Completed) return null
            }

            setForegroundForState(DownloadState.Preparing)
            val mux = MediaVideoAudioMuxer().mux(videoFile, audioFile, outputFile)
            if (mux.isFailure) {
                val error = mux.exceptionOrNull()
                diagnostics.log(
                    DiagnosticLevel.ERROR,
                    "VIDEO_AUDIO_MUX_FAILED",
                    error?.message ?: "mux_failed",
                    "download.mux",
                    mapOf(
                        "task_id" to task.id,
                        "video_size_bytes" to videoFile.length().toString(),
                        "audio_size_bytes" to audioFile.length().toString(),
                    ),
                    error,
                )
                persistAdaptiveRecord(
                    repository,
                    task.id,
                    DownloadState.Failed(DownloadFailure.StorageError),
                    System.currentTimeMillis(),
                )
                return null
            }

            runCatching { videoFile.delete() }
            runCatching { audioFile.delete() }
            val now = System.currentTimeMillis()
            persistAdaptiveRecord(
                repository,
                task.id,
                DownloadState.Downloading(outputFile.length(), outputFile.length()),
                now,
            )
            return queue.applyState(task.id, DownloadState.Completed, now)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The enclosing worker handler alone persists explicit, current user intent.
            throw cancelled
        } catch (error: Throwable) {
            diagnostics.log(
                DiagnosticLevel.ERROR,
                "ADAPTIVE_MUX_UNEXPECTED",
                error.message ?: error::class.simpleName.orEmpty(),
                "download.mux",
                mapOf("task_id" to task.id),
                error,
            )
            persistAdaptiveRecord(
                repository,
                task.id,
                DownloadState.Failed(DownloadFailure.NetworkError),
                System.currentTimeMillis(),
            )
            return null
        }
    }
}
