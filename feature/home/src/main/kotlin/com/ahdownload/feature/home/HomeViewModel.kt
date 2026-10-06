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
    private val onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean = { _, _ -> false },
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
                when (val validation = resolver.validate(candidate, validationOperationId)) {
                    is CandidateValidationResult.Valid -> {
                        val queued = onDownloadRequested(
                            validation.candidate.copy(sourceUrl = validation.finalUrl),
                            state.resolution.title,
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
                                "candidate_id" to candidate.id,
                                "operation_id" to validationOperationId,
                            ),
                            null,
                        )
                        _uiState.value = _uiState.value.copy(
                            validatingCandidateId = null,
                            error = "تم رفض مصدر الوسائط: " + validation.failure,
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
        private val onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean,
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
                ),
                onDownloadRequested = onDownloadRequested,
            ) as T
        }
    }
}
