package com.ahdownload.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.app.settings.DownloadPreferences
import com.ahdownload.app.settings.DownloadPreferencesStore
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.OkHttpTextClient
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.SmartResultEngine
import com.ahdownload.domain.resolver.youtube.YouTubeSearchProvider
import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchProvider
import com.ahdownload.domain.validation.CandidateValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class HomeMode {
    Link,
    Search,
}

enum class ResultFilter(val label: String) {
    All("الكل"),
    Video("فيديو"),
    Audio("صوت"),
    Image("صور"),
    Other("ملفات"),
}

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
    val recentLinks: List<RecentLink> = emptyList(),
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<ContentSearchItem> = emptyList(),
    val searchError: String? = null,
    val mode: HomeMode = HomeMode.Link,
    val showAll: Boolean = false,
    val resultFilter: ResultFilter = ResultFilter.All,
    val selectedSearchIds: Set<String> = emptySet(),
    val batchDownloading: Boolean = false,
    val batchIndex: Int = 0,
    val batchTotal: Int = 0,
    val batchQueued: Int = 0,
    val batchError: String? = null,
)

class HomeViewModel(
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val analyzer: LinkAnalyzer = LinkAnalyzer(),
    private val resolver: HomeResolver,
    private val recentLinkStore: RecentLinkStore,
    private val searchProvider: ContentSearchProvider,
    private val onDownloadRequested: suspend (MediaCandidate, String?, String?, String?) -> DownloadEnqueueResult = { _, _, _, _ ->
        DownloadEnqueueResult.REJECTED
    },
    private val preferencesStore: DownloadPreferencesStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(recentLinks = recentLinkStore.list()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    fun onUrlChanged(value: String) {
        analysisJob?.cancel()
        analysisJob = null
        searchJob?.cancel()
        searchJob = null
        _uiState.value = HomeUiState(
            url = value,
            recentLinks = _uiState.value.recentLinks,
            searchQuery = _uiState.value.searchQuery,
            mode = _uiState.value.mode,
            showAll = false,
            resultFilter = ResultFilter.All,
        )
    }

    fun searchContent(query: String = _uiState.value.searchQuery) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searching = false,
                searchResults = emptyList(),
                searchError = "اكتب كلمة أو جملة للبحث أولًا.",
            )
            return
        }

        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            searchQuery = normalized,
            searching = true,
            searchResults = emptyList(),
            searchError = null,
            selectedSearchIds = emptySet(),
            batchDownloading = false,
            batchIndex = 0,
            batchTotal = 0,
            batchQueued = 0,
            batchError = null,
        )

        searchJob = viewModelScope.launch {
            try {
                val results = searchProvider.search(normalized)
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchResults = results,
                    searchError = if (results.isEmpty()) "لم نجد نتائج مطابقة حاليًا." else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log(
                    DiagnosticLevel.WARNING,
                    "SEARCH_FAILED",
                    error.message ?: error::class.simpleName.orEmpty(),
                    "home.search",
                    mapOf("query_length" to normalized.length.toString()),
                    error,
                )
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchResults = emptyList(),
                    searchError = "تعذر تنفيذ البحث الآن. أعد المحاولة.",
                )
            }
        }
    }

    fun setMode(mode: HomeMode) {
        if (mode == HomeMode.Search) {
            enterSearchMode()
        } else {
            analysisJob?.cancel()
            searchJob?.cancel()
            _uiState.value = _uiState.value.copy(
                mode = HomeMode.Link,
                searching = false,
                searchResults = emptyList(),
                searchError = null,
                showAll = false,
                resultFilter = ResultFilter.All,
            )
        }
    }

    fun toggleShowAll() {
        _uiState.value = _uiState.value.copy(showAll = !_uiState.value.showAll)
    }

    fun setResultFilter(filter: ResultFilter) {
        _uiState.value = _uiState.value.copy(resultFilter = filter)
    }

    fun enterSearchMode() {
        analysisJob?.cancel()
        analysisJob = null
        _uiState.value = _uiState.value.copy(
            url = "",
            analyzing = false,
            resolving = false,
            result = null,
            resolution = null,
            selectedCandidateId = null,
            validatingCandidateId = null,
            error = null,
            downloadQueued = false,
            mode = HomeMode.Search,
            showAll = false,
            resultFilter = ResultFilter.All,
        )
    }

    fun onSearchQueryChanged(value: String) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            searchQuery = value,
            searchResults = if (value == _uiState.value.searchQuery) _uiState.value.searchResults else emptyList(),
            searchError = null,
            selectedSearchIds = if (value == _uiState.value.searchQuery) _uiState.value.selectedSearchIds else emptySet(),
            batchError = null,
        )
    }

    fun toggleSearchSelection(id: String) {
        if (_uiState.value.batchDownloading) return
        val selected = _uiState.value.selectedSearchIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        _uiState.value = _uiState.value.copy(selectedSearchIds = selected)
    }

    fun clearSearchSelection() {
        _uiState.value = _uiState.value.copy(selectedSearchIds = emptySet())
    }

    fun downloadSelectedSearchResults(items: List<ContentSearchItem>) {
        if (_uiState.value.batchDownloading) return
        val selected = items.filter { it.id in _uiState.value.selectedSearchIds }.take(8)
        if (selected.isEmpty()) {
            _uiState.value = _uiState.value.copy(batchError = "حدد نتيجة واحدة على الأقل.")
            return
        }

        _uiState.value = _uiState.value.copy(
            batchDownloading = true,
            batchIndex = 0,
            batchTotal = selected.size,
            batchQueued = 0,
            batchError = null,
        )

        viewModelScope.launch {
            var queuedCount = 0
            var failures = 0
            selected.forEachIndexed { index, item ->
                _uiState.value = _uiState.value.copy(
                    batchIndex = index + 1,
                    batchQueued = queuedCount,
                )
                try {
                    val link = analyzer.analyze(item.url)
                    if (link == null) {
                        failures++
                        return@forEachIndexed
                    }
                    val operationId = UUID.randomUUID().toString()
                    when (val resolution = resolver.resolve(link, operationId)) {
                        is ResolverResult.Success -> {
                            val smart = SmartResultEngine().build(resolution.candidates)
                            val selectedId = chooseDefaultCandidate(resolution.candidates, smart, preferencesStore.read())
                            val candidate = resolution.candidates.firstOrNull { it.id == selectedId }
                            if (candidate == null) {
                                failures++
                                return@forEachIndexed
                            }
                            when (val validation = resolver.validate(candidate, operationId)) {
                                is CandidateValidationResult.Valid -> {
                                    val queued = onDownloadRequested(
                                        validation.candidate.copy(sourceUrl = validation.finalUrl),
                                        item.title,
                                        link.normalizedUrl,
                                        item.thumbnailUrl,
                                    )
                                    if (queued == DownloadEnqueueResult.QUEUED) queuedCount++ else failures++
                                }
                                is CandidateValidationResult.Invalid -> failures++
                            }
                        }
                        is ResolverResult.Failure -> failures++
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failures++
                    logger.log(
                        DiagnosticLevel.WARNING,
                        "SEARCH_BATCH_ITEM_FAILED",
                        error.message ?: error::class.simpleName.orEmpty(),
                        "home.search.batch",
                        mapOf("item_id" to item.id),
                        error,
                    )
                }
                _uiState.value = _uiState.value.copy(batchQueued = queuedCount)
            }
            _uiState.value = _uiState.value.copy(
                batchDownloading = false,
                batchQueued = queuedCount,
                selectedSearchIds = emptySet(),
                batchError = if (failures > 0) {
                    "أضيف $queuedCount للتنزيل وتعذر تجهيز $failures."
                } else {
                    "تمت إضافة $queuedCount عناصر إلى قائمة التنزيل."
                },
            )
        }
    }

    fun openSearchResult(item: ContentSearchItem) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(searchQuery = item.title, mode = HomeMode.Link)
        onUrlChanged(item.url)
        analyze()
    }

    fun selectRecentLink(link: RecentLink) {
        _uiState.value = HomeUiState(
            url = link.url,
            recentLinks = _uiState.value.recentLinks,
            mode = HomeMode.Link,
            showAll = false,
            resultFilter = ResultFilter.All,
        )
        analyze()
    }

    fun clearRecentLinks() {
        recentLinkStore.clear()
        _uiState.value = _uiState.value.copy(recentLinks = emptyList())
    }

    fun analyze() {
        val current = _uiState.value.url.trim()
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
                selectedCandidateId = null,
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
            url = current,
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
                        val smart = SmartResultEngine().build(resolution.candidates)
                        val selectedId = chooseDefaultCandidate(resolution.candidates, smart, preferencesStore.read())
                        val updatedRecent = if (resolution.candidates.isNotEmpty()) {
                            recentLinkStore.add(
                                url = link.normalizedUrl,
                                title = resolution.title,
                                platform = link.platform.name,
                                thumbnailUrl = resolution.thumbnailUrl,
                            )
                            recentLinkStore.list()
                        } else {
                            _uiState.value.recentLinks
                        }

                        _uiState.value = _uiState.value.copy(
                            analyzing = false,
                            resolving = false,
                            resolution = resolution,
                            selectedCandidateId = selectedId,
                            recentLinks = updatedRecent,
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
                            analyzing = false,
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
        val candidate = state.resolution?.candidates
            ?.firstOrNull { it.id == state.selectedCandidateId }
            ?: return

        val validationOperationId = UUID.randomUUID().toString()
        logger.log(
            DiagnosticLevel.INFO,
            "DOWNLOAD_PREPARE_STARTED",
            "بدء تجهيز التنزيل والتحقق من المصدر",
            "download.prepare",
            mapOf(
                "operation_id" to validationOperationId,
                "candidate_id" to candidate.id,
            ),
            null,
        )

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
                            state.resolution.thumbnailUrl,
                        )
                        val message = when (queued) {
                            DownloadEnqueueResult.QUEUED -> null
                            DownloadEnqueueResult.DUPLICATE -> "هذا المحتوى موجود بالفعل في سجل التنزيلات."
                            DownloadEnqueueResult.INVALID_CUSTOM_LOCATION -> "مجلد التنزيل المحدد غير متاح. اختر مجلدًا آخر من الإعدادات."
                            DownloadEnqueueResult.STORAGE_UNAVAILABLE -> "مساحة التخزين أو مسار التنزيل غير متاح حاليًا."
                            DownloadEnqueueResult.REJECTED -> "تعذر إضافة التنزيل إلى قائمة الانتظار."
                        }
                        if (queued != DownloadEnqueueResult.QUEUED) {
                            logger.log(
                                DiagnosticLevel.ERROR,
                                "QUEUE",
                                queued.name,
                                "download.queue",
                                mapOf("candidate_id" to candidateToValidate.id),
                                null,
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            validatingCandidateId = null,
                            downloadQueued = queued == DownloadEnqueueResult.QUEUED,
                            error = message,
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
                                    "تعذر الحصول على مصدر صالح حاليًا. يمكنك إعادة المحاولة."
                                else ->
                                    "تعذر التحقق من مصدر الوسائط. يمكنك إعادة المحاولة."
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

    private fun chooseDefaultCandidate(
        candidates: List<MediaCandidate>,
        smart: com.ahdownload.domain.resolver.SmartResultSet,
        preferences: DownloadPreferences,
    ): String? {
        if (!preferences.smartDownload) {
            return smart.bestOverall?.candidate?.id
                ?: smart.bestQuality?.candidate?.id
                ?: candidates.firstOrNull()?.id
        }

        val video = candidates
            .filter { it.format.kind == MediaKind.Video }
            .sortedWith(
                compareByDescending<MediaCandidate> { it.format.height ?: 0 }
                    .thenByDescending { it.format.bitrateKbps ?: 0 },
            )
        val audio = candidates
            .filter { it.format.kind == MediaKind.Audio }
            .sortedByDescending { it.format.bitrateKbps ?: 0 }

        val preferredHeight = preferences.videoQuality.maxHeight
        val preferredVideo = if (preferredHeight == null) {
            video.firstOrNull()
        } else {
            video.firstOrNull { (it.format.height ?: 0) <= preferredHeight } ?: video.lastOrNull()
        }
        val preferredBitrate = preferences.audioBitrate.kbps
        val preferredAudio = if (preferredBitrate <= 0) {
            audio.firstOrNull()
        } else {
            audio.firstOrNull { (it.format.bitrateKbps ?: 0) <= preferredBitrate } ?: audio.lastOrNull()
        }

        return when {
            preferredVideo != null -> preferredVideo.id
            preferredAudio != null -> preferredAudio.id
            else -> smart.bestOverall?.candidate?.id ?: candidates.firstOrNull()?.id
        }
    }

    fun downloadCandidate(id: String) {
        if (_uiState.value.resolution?.candidates?.any { it.id == id } != true) return
        selectCandidate(id)
        downloadSelected()
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
        private val onDownloadRequested: suspend (MediaCandidate, String?, String?, String?) -> DownloadEnqueueResult,
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
                recentLinkStore = RecentLinkStore(context.applicationContext),
                searchProvider = YouTubeSearchProvider(OkHttpTextClient()),
                preferencesStore = DownloadPreferencesStore(context.applicationContext),
                onDownloadRequested = onDownloadRequested,
            ) as T
        }
    }
}
