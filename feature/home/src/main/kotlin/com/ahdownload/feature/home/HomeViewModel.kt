package com.ahdownload.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.validation.CandidateValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class HomeUiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val resolving: Boolean = false,
    val result: MediaLink? = null,
    val resolution: ResolverResult.Success? = null,
    val selectedCandidateId: String? = null,
    val validatingCandidateId: String? = null,
    val error: String? = null,
    val downloadQueued: Boolean = false,
)

class HomeViewModel(
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val analyzer: LinkAnalyzer = LinkAnalyzer(),
    private val resolver: HomeResolver,
    private val onDownloadRequested: suspend (MediaCandidate, String?, String?) -> Boolean = { _, _, _ -> false },
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null
    private var downloadJob: Job? = null

    fun onUrlChanged(value: String) {
        analysisJob?.cancel()
        analysisJob = null
        _uiState.value = HomeUiState(url = value)
    }

    fun analyze() {
        val current = _uiState.value.url
        val link = analyzer.analyze(current)
        if (link == null) {
            logger.log(
                DiagnosticLevel.ERROR,
                "INVALID_URL",
                "الرابط غير صالح أو غير مدعوم",
                "home.analyze",
                emptyMap(),
                null,
            )
            _uiState.value = _uiState.value.copy(
                analyzing = false,
                resolving = false,
                result = null,
                resolution = null,
                error = "الرابط غير صالح أو غير مدعوم.",
            )
            return
        }

        analysisJob?.cancel()
        val operationId = UUID.randomUUID().toString()
        logger.log(
            DiagnosticLevel.INFO,
            "ANALYSIS_STARTED",
            "بدء تحليل الرابط",
            "home.analyze",
            mapOf("operation_id" to operationId, "platform" to link.platform.name),
            null,
        )

        _uiState.value = _uiState.value.copy(
            analyzing = true,
            resolving = false,
            result = link,
            resolution = null,
            selectedCandidateId = null,
            validatingCandidateId = null,
            error = null,
            downloadQueued = false,
        )

        analysisJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(analyzing = false, resolving = true)
                when (val resolution = resolver.resolve(link, operationId)) {
                    is ResolverResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            resolving = false,
                            resolution = resolution,
                            selectedCandidateId = resolution.candidates.firstOrNull()?.id,
                            error = if (resolution.candidates.isEmpty()) {
                                "لم يتم العثور على وسائط قابلة للتنزيل."
                            } else {
                                null
                            },
                        )
                    }

                    is ResolverResult.Failure -> {
                        logger.log(
                            DiagnosticLevel.ERROR,
                            "RESOLVER",
                            resolution.code.name,
                            "home.resolve",
                            mapOf(
                                "reason" to (resolution.message ?: "تعذر استخراج الوسائط."),
                                "operation_id" to operationId,
                                "platform" to link.platform.name,
                            ),
                            null,
                        )
                        _uiState.value = _uiState.value.copy(
                            resolving = false,
                            resolution = null,
                            error = resolution.message ?: "تعذر استخراج الوسائط: " + resolution.code,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log(
                    DiagnosticLevel.ERROR,
                    "RESOLVER_UNEXPECTED",
                    error.message ?: error::class.simpleName.orEmpty(),
                    "home.resolve",
                    mapOf(
                        "operation_id" to operationId,
                        "platform" to link.platform.name,
                    ),
                    error,
                )
                _uiState.value = _uiState.value.copy(
                    analyzing = false,
                    resolving = false,
                    resolution = null,
                    error = "حدث خطأ غير متوقع أثناء استخراج الوسائط.",
                )
            }
        }
    }

    fun selectCandidate(id: String) {
        if (_uiState.value.resolution?.candidates?.any { it.id == id } != true) return
        _uiState.value = _uiState.value.copy(
            selectedCandidateId = id,
            error = null,
            downloadQueued = false,
        )
    }

    fun downloadSelected() {
        if (downloadJob?.isActive == true) return

        val state = _uiState.value
        val validationOperationId = UUID.randomUUID().toString()
        logger.log(
            DiagnosticLevel.INFO,
            "DOWNLOAD_PREPARE_STARTED",
            "بدء تجهيز التنزيل والتحقق من المصدر",
            "download.prepare",
            mapOf(
                "operation_id" to validationOperationId,
                "candidate_id" to (state.resolution?.candidates?.firstOrNull { it.id == state.selectedCandidateId }?.id ?: "unknown"),
            ),
            null,
        )
        val candidate = state.resolution?.candidates?.firstOrNull { it.id == state.selectedCandidateId }
            ?: return

        downloadJob = viewModelScope.launch {
            _uiState.value = state.copy(
                validatingCandidateId = candidate.id,
                error = null,
                downloadQueued = false,
            )
            try {
                var candidateToValidate = candidate
                var validation = resolver.validate(candidateToValidate, validationOperationId)
                var youtubeRefreshAttempted = false
                var youtubeFallbackCandidatesChecked = 0

                // YouTube media URLs are signed/short-lived. Refresh exactly once on 403.
                // After the refresh, validate a small, deterministic set of same-kind
                // candidates so a stale top-ranked URL cannot block an otherwise valid
                // source. No blind retries or bypass logic are used.
                val validationFailure = (validation as? CandidateValidationResult.Invalid)?.failure
                val httpStatusFailure =
                    validationFailure as? com.ahdownload.domain.validation.ValidationFailure.HttpStatus
                val youtubeLink = state.result?.takeIf {
                    it.platform == com.ahdownload.domain.model.MediaPlatform.YouTube
                }

                if (httpStatusFailure?.code == 403 && youtubeLink != null) {
                    youtubeRefreshAttempted = true
                    logger.log(
                        DiagnosticLevel.WARNING,
                        "YOUTUBE_CANDIDATE_REFRESH_STARTED",
                        "مصدر YouTube أصبح غير صالح؛ سيتم استخراج مصدر حديث مرة واحدة",
                        "download.refresh",
                        mapOf(
                            "candidate_id" to candidate.id,
                            "candidate_format_id" to candidate.format.id,
                            "operation_id" to validationOperationId,
                            "platform" to "YouTube",
                        ),
                        null,
                    )

                    when (val refreshed = resolver.resolve(youtubeLink, validationOperationId)) {
                        is ResolverResult.Success -> {
                            val refreshedCandidates = refreshed.candidates
                                .filter { it.format.kind == candidate.format.kind }
                                .distinctBy { it.id }
                                .sortedWith(
                                    compareBy<MediaCandidate> { it.id == candidate.id }
                                         .thenByDescending { it.id.startsWith("android-") }
                                        .thenByDescending { it.id.startsWith("embedded-") }
                                        .thenByDescending { it.format.hasVideo }
                                        .thenByDescending { it.format.hasAudio }
                                        .thenByDescending { it.format.height ?: 0 }
                                        .thenByDescending { it.format.bitrateKbps ?: 0 },
                                )
                                .take(3)

                            logger.log(
                                DiagnosticLevel.INFO,
                                "YOUTUBE_CANDIDATE_REFRESH_RESULT",
                                "تم استخراج مصدر YouTube حديث وإعادة التحقق",
                                "download.refresh",
                                mapOf(
                                    "old_candidate_id" to candidate.id,
                                    "new_candidate_id" to (refreshedCandidates.firstOrNull()?.id ?: "none"),
                                    "candidate_count" to refreshed.candidates.size.toString(),
                                    "fallback_candidate_count" to refreshedCandidates.size.toString(),
                                    "operation_id" to validationOperationId,
                                ),
                                null,
                            )

                            for (refreshedCandidate in refreshedCandidates) {
                                candidateToValidate = refreshedCandidate
                                youtubeFallbackCandidatesChecked++
                                validation = resolver.validate(
                                    refreshedCandidate,
                                    validationOperationId,
                                )
                                logger.log(
                                    if (validation is CandidateValidationResult.Valid) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                                    "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION",
                                    if (validation is CandidateValidationResult.Valid) "تم قبول مصدر YouTube البديل" else "تم رفض مصدر YouTube البديل",
                                    "download.refresh.validation",
                                    mapOf(
                                        "candidate_id" to refreshedCandidate.id,
                                        "candidate_format_id" to refreshedCandidate.format.id,
                                        "candidate_kind" to refreshedCandidate.format.kind.name,
                                        "height" to (refreshedCandidate.format.height?.toString() ?: "unknown"),
                                        "bitrate_kbps" to (refreshedCandidate.format.bitrateKbps?.toString() ?: "unknown"),
                                        "has_audio" to refreshedCandidate.format.hasAudio.toString(),
                                        "validation_result" to if (validation is CandidateValidationResult.Valid) "valid" else "invalid",
                                        "operation_id" to validationOperationId,
                                    ),
                                    null,
                                )
                                if (validation is CandidateValidationResult.Valid) break

                                val refreshedFailure =
                                    (validation as CandidateValidationResult.Invalid).failure
                                val refreshedHttpFailure =
                                    refreshedFailure as? com.ahdownload.domain.validation.ValidationFailure.HttpStatus

                                // Only continue to another candidate for a rejected HTTP
                                // source. Parser/content-type/storage failures are not
                                // candidates for blind fallback.
                                if (refreshedHttpFailure?.code != 403) break
                            }
                        }

                        is ResolverResult.Failure -> {
                            logger.log(
                                DiagnosticLevel.WARNING,
                                "YOUTUBE_CANDIDATE_REFRESH_FAILED",
                                refreshed.message ?: refreshed.code.name,
                                "download.refresh",
                                mapOf(
                                    "candidate_id" to candidate.id,
                                    "operation_id" to validationOperationId,
                                    "failure_code" to refreshed.code.name,
                                ),
                                null,
                            )
                        }
                    }
                }


                when (validation) {
                    is CandidateValidationResult.Valid -> {
                        val queued = onDownloadRequested(
                            validation.candidate.copy(sourceUrl = validation.finalUrl),
                            state.resolution.title,
                            state.result?.normalizedUrl,
                        )
                        if (!queued) {
                            logger.log(
                                DiagnosticLevel.ERROR,
                                "QUEUE",
                                "QUEUE_REJECTED",
                                "download.queue",
                                emptyMap(),
                                null,
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            validatingCandidateId = null,
                            downloadQueued = queued,
                            error = if (queued) null else "تعذر إضافة التنزيل إلى قائمة الانتظار.",
                        )
                    }

                    is CandidateValidationResult.Invalid -> {
                        logger.log(
                            DiagnosticLevel.ERROR,
                            "MEDIA_VALIDATION",
                            validation.failure.toString(),
                            "download.validate",
                            mapOf(
                                "candidate_id" to candidateToValidate.id,
                                "operation_id" to validationOperationId,
                                "youtube_refresh_attempted" to youtubeRefreshAttempted.toString(),
                                "youtube_fallback_candidates_checked" to youtubeFallbackCandidatesChecked.toString(),
                            ),
                            null,
                        )
                        _uiState.value = _uiState.value.copy(
                            validatingCandidateId = null,
                            error = when (validation.failure) {
                                is com.ahdownload.domain.validation.ValidationFailure.HttpStatus ->
                                    "لم نتمكن من الحصول على مصدر صالح من المنصة حاليًا. يمكنك فتح التشخيص لمعرفة التفاصيل."
                                else ->
                                    "تعذر التحقق من مصدر الوسائط. يمكنك فتح التشخيص لمعرفة التفاصيل."
                            },
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log(
                    DiagnosticLevel.ERROR,
                    "DOWNLOAD_PREPARE_UNEXPECTED",
                    error.message ?: error::class.simpleName.orEmpty(),
                    "download.prepare",
                    mapOf(
                        "candidate_id" to candidate.id,
                        "operation_id" to validationOperationId,
                    ),
                    error,
                )
                _uiState.value = _uiState.value.copy(
                    validatingCandidateId = null,
                    error = "حدث خطأ غير متوقع أثناء تجهيز التنزيل.",
                )
            }
        }
    }

    fun downloadAudio() {
        val candidate = _uiState.value.resolution?.candidates?.firstOrNull {
            it.format.kind == MediaKind.Audio
        }
        if (candidate != null) {
            selectCandidate(candidate.id)
            downloadSelected()
        }
    }

    class Factory(
        private val onDownloadRequested: suspend (MediaCandidate, String?, String?) -> Boolean,
        private val logger: DiagnosticLogger,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(
                logger = logger,
                resolver = HomeResolver(
                    logger = logger,
                    sessionProvider = AndroidYouTubeSessionProvider(context.applicationContext),
                    browserMediaSessionProvider = AndroidBrowserMediaSessionProvider(context.applicationContext),
                ),
                onDownloadRequested = onDownloadRequested,
            ) as T
        }
    }
}
