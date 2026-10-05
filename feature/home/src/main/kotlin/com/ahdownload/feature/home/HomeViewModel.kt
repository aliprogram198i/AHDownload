package com.ahdownload.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.validation.CandidateValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val analyzer: LinkAnalyzer = LinkAnalyzer(),
    private val resolver: HomeResolver = HomeResolver(),
    private val onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean = { _, _ -> false },
    private val onLogError: (String, String, String?) -> Unit = { _, _, _ -> },
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onUrlChanged(value: String) {
        _uiState.value = HomeUiState(url = value)
    }

    fun analyze() {
        val current = _uiState.value.url
        val link = analyzer.analyze(current)
        if (link == null) {
            onLogError("INVALID_URL", "الرابط غير صالح أو غير مدعوم", "تم رفض الرابط قبل بدء Resolver.")
            _uiState.value = _uiState.value.copy(
                analyzing = false,
                resolving = false,
                result = null,
                resolution = null,
                error = "الرابط غير صالح أو غير مدعوم.",
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            analyzing = true,
            resolving = false,
            result = link,
            resolution = null,
            selectedCandidateId = null,
            error = null,
            downloadQueued = false,
        )

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(analyzing = false, resolving = true)
            when (val resolution = resolver.resolve(link)) {
                is ResolverResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        resolving = false,
                        resolution = resolution,
                        selectedCandidateId = resolution.candidates.firstOrNull()?.id,
                        error = if (resolution.candidates.isEmpty()) "لم يتم العثور على وسائط قابلة للتنزيل." else null,
                    )
                }
                is ResolverResult.Failure -> {
                    onLogError(
                        "RESOLVER",
                        resolution.code.name,
                        resolution.message ?: "تعذر استخراج الوسائط.",
                    )
                    _uiState.value = _uiState.value.copy(
                        resolving = false,
                        resolution = null,
                        error = resolution.message ?: "تعذر استخراج الوسائط: " + resolution.code,
                    )
                }
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
        val state = _uiState.value
        val candidate = state.resolution?.candidates?.firstOrNull { it.id == state.selectedCandidateId }
            ?: return

        viewModelScope.launch {
            _uiState.value = state.copy(validatingCandidateId = candidate.id, error = null, downloadQueued = false)
            when (val validation = resolver.validate(candidate)) {
                is CandidateValidationResult.Valid -> {
                    val queued = onDownloadRequested(
                        validation.candidate.copy(sourceUrl = validation.finalUrl),
                        state.resolution.title,
                    )
                    if (!queued) {
                        onLogError("QUEUE", "QUEUE_REJECTED", "تعذر إضافة المصدر المتحقق منه إلى قائمة التنزيل.")
                    }
                    _uiState.value = _uiState.value.copy(
                        validatingCandidateId = null,
                        downloadQueued = queued,
                        error = if (queued) null else "تعذر إضافة التنزيل إلى قائمة الانتظار.",
                    )
                }
                is CandidateValidationResult.Invalid -> {
                    onLogError("MEDIA_VALIDATION", validation.failure.toString(), "تم رفض المصدر قبل إدخاله إلى WorkManager.")
                    _uiState.value = _uiState.value.copy(
                        validatingCandidateId = null,
                        error = "تم رفض مصدر الوسائط: " + validation.failure,
                    )
                }
            }
        }
    }

    fun downloadAudio() {
        val candidate = _uiState.value.resolution?.candidates?.firstOrNull { it.format.kind == MediaKind.Audio }
        if (candidate != null) {
            selectCandidate(candidate.id)
            downloadSelected()
        }
    }

    class Factory(
        private val onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean,
        private val onLogError: (String, String, String?) -> Unit,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(
                onDownloadRequested = onDownloadRequested,
                onLogError = onLogError,
            ) as T
        }
    }
}
