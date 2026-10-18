package com.ahdownload.feature.home

import com.ahdownload.core.designsystem.tracedClickable

import com.ahdownload.core.designsystem.DiagnosticButton
import com.ahdownload.core.designsystem.DiagnosticOutlinedButton
import com.ahdownload.core.designsystem.DiagnosticTextButton
import com.ahdownload.core.designsystem.DiagnosticIconButton
import com.ahdownload.core.designsystem.DiagnosticFilterChip

import androidx.activity.compose.BackHandler
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.ahdownload.core.designsystem.AHProgressBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DownloadPreferencesProvider
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHBottomNavDestination
import com.ahdownload.core.designsystem.AHBottomNavigationBar
import com.ahdownload.core.designsystem.AHCard
import com.ahdownload.core.designsystem.AHEmptyState
import com.ahdownload.core.designsystem.AHSectionHeader
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.favorites.FavoriteItem
import com.ahdownload.domain.favorites.FavoriteKey
import com.ahdownload.domain.favorites.FavoriteRepository
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.resolver.MediaResultRecommendation
import com.ahdownload.domain.resolver.SmartResultEngine
import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchKind
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun HomeRoute(
    initialUrl: String? = null,
    onDownloadRequested: suspend (MediaCandidate, String?, String?, String?) -> DownloadEnqueueResult,
    onAudioOnlyRequested: suspend (MediaCandidate, AudioOutputFormat, String?, String?, String?) -> DownloadEnqueueResult,
    logger: DiagnosticLogger,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onInitialUrlConsumed: () -> Unit,
    uiTraceLogger: UiTraceLogger,
    activeDownloads: Int = 0,
    preferencesProvider: DownloadPreferencesProvider,
    favoriteRepository: FavoriteRepository,
    onAnalysisPerformance: (LinkAnalysisPerformance) -> Unit = {},
    initialMode: HomeMode = HomeMode.Link,
    onNavigateHome: () -> Unit = {},
    onOpenYouTubeSearch: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
) {
    val context = LocalContext.current
    val favorites by favoriteRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteScope = rememberCoroutineScope()
    val factory = remember(onDownloadRequested, onAudioOnlyRequested, logger, context, preferencesProvider, onAnalysisPerformance) {
        HomeViewModel.Factory(
            onDownloadRequested = onDownloadRequested,
            onAudioOnlyRequested = onAudioOnlyRequested,
            logger = logger,
            context = context,
            preferencesProvider = preferencesProvider,
            onAnalysisPerformance = onAnalysisPerformance,
        )
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialMode) {
        viewModel.setMode(initialMode)
    }

    LaunchedEffect(initialUrl) {
        initialUrl?.takeIf { it.isNotBlank() }?.let {
            uiTraceLogger.interaction(
                "HOME",
                "screen",
                "received_initial_url",
                mapOf("source" to "external_intent"),
            )
            viewModel.setUrlAndAnalyze(it)
            onInitialUrlConsumed()
        }
    }

    HomeScreen(
        state = state,
        logger = logger,
        onUrlChanged = viewModel::onUrlChanged,
        onPasteLink = viewModel::setUrlAndAnalyze,
        onAnalyze = viewModel::analyze,
        onSelectCandidate = viewModel::selectCandidate,
        onSelectAudioCandidate = viewModel::selectAudioCandidate,
        onSelectAudioOutputFormat = viewModel::selectAudioOutputFormat,
        onDownloadCandidate = viewModel::downloadCandidate,
        onDownloadAudio = viewModel::downloadAudio,
        onModeChanged = viewModel::setMode,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchFilterChanged = viewModel::setSearchFilter,
        onSearch = viewModel::searchContent,
        onSearchResultSelected = viewModel::openSearchResult,
        onSearchSelectionToggle = viewModel::toggleSearchSelection,
        onClearSearchSelection = viewModel::clearSearchSelection,
        onBatchDownload = viewModel::downloadSelectedSearchResults,
        onOpenSettings = onOpenSettings,
        onOpenDownloads = onOpenDownloads,
        onNavigateHome = onNavigateHome,
        onOpenYouTubeSearch = onOpenYouTubeSearch,
        onOpenBrowser = onOpenBrowser,
        uiTraceLogger = uiTraceLogger,
        activeDownloads = activeDownloads,
        favoriteItems = favorites,
        currentFavorite = state.result?.normalizedUrl?.let { favoriteRepository.isFavorite(it) } == true,
        onToggleFavorite = {
            state.result?.let { result ->
                val url = result.normalizedUrl
                val key = FavoriteKey.fromUrl(url)
                val existing = favorites.any { FavoriteKey.fromUrl(it.url) == key }
                favoriteScope.launch {
                    favoriteRepository.setFavorite(
                        FavoriteItem(
                            id = key,
                            url = url,
                            title = state.resolution?.title,
                            thumbnailUrl = state.resolution?.thumbnailUrl,
                            createdAtEpochMs = System.currentTimeMillis(),
                        ),
                        favorite = !existing,
                    )
                }
            }
        },
        onFavoriteSelected = { item ->
            viewModel.setUrlAndAnalyze(item.url)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: HomeUiState,
    logger: DiagnosticLogger,
    onUrlChanged: (String) -> Unit,
    onPasteLink: (String) -> Unit,
    onAnalyze: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onSelectAudioCandidate: (String) -> Unit,
    onSelectAudioOutputFormat: (AudioOutputFormat) -> Unit,
    onDownloadCandidate: (String) -> Unit,
    onDownloadAudio: (String?) -> Unit,
    onModeChanged: (HomeMode) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchFilterChanged: (YouTubeSearchFilter) -> Unit,
    onSearch: () -> Unit,
    onSearchResultSelected: (ContentSearchItem) -> Unit,
    onSearchSelectionToggle: (String) -> Unit,
    onClearSearchSelection: () -> Unit,
    onBatchDownload: (List<ContentSearchItem>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenYouTubeSearch: () -> Unit,
    onOpenBrowser: () -> Unit,
    uiTraceLogger: UiTraceLogger,
    activeDownloads: Int = 0,
    favoriteItems: List<FavoriteItem> = emptyList(),
    currentFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onFavoriteSelected: (FavoriteItem) -> Unit = {},
) {
    val mode = state.mode
    var showResultsScreen by remember { mutableStateOf(false) }
    val androidContext = LocalContext.current
    val visibleSearchResults = remember(state.searchResults, state.searchFilter) {
        filterYouTubeSearchResults(state.searchResults, state.searchFilter)
    }
    val searchResultCounts = remember(state.searchResults) {
        countYouTubeSearchResults(state.searchResults)
    }
    val candidates = state.resolution?.candidates.orEmpty()
    val resultSet = remember(candidates) { SmartResultEngine().build(candidates) }
    val resultKind = state.result?.kind ?: MediaKind.Unknown
    val primaryOptions = when (resultKind) {
        MediaKind.Video -> resultSet.video
        MediaKind.Audio -> resultSet.audio
        MediaKind.Image -> resultSet.other.filter { it.candidate.format.kind == MediaKind.Image }
        MediaKind.Unknown -> resultSet.all
    }
    val directAudioAvailable = resultSet.directAudioSourceAvailable
    val directVideoSourceAvailable = candidates.any {
        it.id == "direct" && it.format.kind == MediaKind.Video && it.format.hasVideo
    }
    val videoOptionGroups = classifyVideoPickerOptions(primaryOptions, directAudioAvailable)
    val availableVideoOptions = videoOptionGroups.mainOptions
    val videoOnlyFallbackOptions = videoOptionGroups.videoOnlyFallback
    val rawVideoSourceCount = primaryOptions.count {
        it.candidate.format.kind == MediaKind.Video && it.candidate.format.hasVideo
    }
    val unresolvedVideoSourceCount =
        (rawVideoSourceCount - availableVideoOptions.size - videoOnlyFallbackOptions.size).coerceAtLeast(0)
    val audioExtractionAvailable = directAudioAvailable || directVideoSourceAvailable || availableVideoOptions.any {
        it.candidate.format.kind == MediaKind.Video &&
            it.candidate.format.hasVideo &&
            it.candidate.format.hasAudio
    }
    val actionableAudioOptions = if (audioExtractionAvailable) resultSet.audio else emptyList()
    val actionableOptions = (
        availableVideoOptions + videoOnlyFallbackOptions + actionableAudioOptions +
            primaryOptions.filter { it.group == com.ahdownload.domain.resolver.MediaResultGroup.Other }
        ).distinctBy { it.candidate.id }
    val actionableOptionIds = actionableOptions.mapTo(mutableSetOf()) { it.candidate.id }
    val smallestActionable = actionableOptions
        .filter { (it.candidate.format.fileSizeBytes ?: 0L) > 0L }
        .minByOrNull { it.candidate.format.fileSizeBytes ?: Long.MAX_VALUE }
    val uiContext = rememberUiTraceContext()

    LaunchedEffect(state.resolution) {
        showResultsScreen = state.mode == HomeMode.Link && state.resolution != null
    }
    LaunchedEffect(state.mode) {
        if (state.mode != HomeMode.Link) showResultsScreen = false
    }

    BackHandler(enabled = showResultsScreen && state.mode == HomeMode.Link) {
        uiTraceLogger.interaction("HOME", "results_screen", "system_back_to_link")
        showResultsScreen = false
    }

    DisposableEffect(Unit) {
        uiTraceLogger.interaction("HOME", "screen", "entered")
        onDispose {
            uiTraceLogger.interaction("HOME", "screen", "exited")
        }
    }

    LaunchedEffect(
        state.mode,
        showResultsScreen,
        state.url,
        state.analyzing,
        state.resolving,
        state.resolution?.title,
        candidates.size,
        state.selectedCandidateId,
        state.selectedAudioCandidateId,
        state.selectedAudioOutputFormat,
        state.validatingCandidateId,
        state.error,
        state.downloadQueued,
        state.showAll,
        state.resultFilter,
        state.searchQuery.length,
        state.searching,
        state.searchResults.size,
        state.searchFilter,
        visibleSearchResults.size,
        state.searchError,
        state.selectedSearchIds.size,
        state.batchDownloading,
        state.batchIndex,
        state.batchTotal,
        state.batchQueued,
        state.batchError,
        favoriteItems.size,
        currentFavorite,
        activeDownloads,
    ) {
        val components = buildList {
            add("topbar")
            add("bottom_navigation")
            if (showResultsScreen && state.mode == HomeMode.Link && state.resolution != null) {
                add("results_screen_back")
                add("unified_download_result")
            } else {
                when (state.mode) {
                    HomeMode.Link -> {
                        add("url_input")
                        add("analyze_button")
                        if (state.url.isNotBlank()) add("clear_input_button")
                        add("paste_input_button")
                        if (state.analyzing || state.resolving) add("loading_state")
                    }
                    HomeMode.Search -> {
                        add("youtube_search")
                        if (state.searching) add("search_loading")
                        if (state.searchResults.isNotEmpty()) add("search_results")
                    }
                }
            }
            if (state.error != null && !(showResultsScreen && state.mode == HomeMode.Link)) add("error_card")
            if (state.downloadQueued && !(showResultsScreen && state.mode == HomeMode.Link)) add("download_success")
            if (state.searching && state.mode != HomeMode.Search) add("background_search")
            if (state.batchDownloading) add("batch_download_progress")
        }.joinToString(",")

        uiTraceLogger.snapshot(
            screen = "HOME",
            component = "HomeScreen",
            components = components,
            stateSummary = "url_empty=" + state.url.isBlank() +
                ";analyzing=" + state.analyzing +
                ";resolving=" + state.resolving +
                ";platform=" + (state.result?.platform?.name ?: "none") +
                ";candidates=" + candidates.size +
                ";selected=" + (state.selectedCandidateId ?: "none") +
                ";selected_audio=" + (state.selectedAudioCandidateId ?: "none") +
                ";audio_output=" + (state.selectedAudioOutputFormat?.name ?: "none") +
                ";validating=" + (state.validatingCandidateId ?: "none") +
                ";error=" + (state.error != null) +
                ";show_all=" + state.showAll +
                ";filter=" + state.resultFilter.name +
                ";queued=" + state.downloadQueued +
                ";search_query_length=" + state.searchQuery.length +
                ";searching=" + state.searching +
                ";search_results=" + state.searchResults.size +
                ";search_filter=" + state.searchFilter.name +
                ";search_visible_results=" + visibleSearchResults.size +
                ";search_selected=" + state.selectedSearchIds.size +
                ";batch_downloading=" + state.batchDownloading +
                ";batch=" + state.batchIndex + "/" + state.batchTotal +
                ";batch_queued=" + state.batchQueued +
                ";search_error=" + (state.searchError != null) +
                ";recent_links=" + state.recentLinks.size +
                ";favorites=" + favoriteItems.size +
                ";current_favorite=" + currentFavorite +
                ";active_downloads=" + activeDownloads,
            context = uiContext,
        )
    }

    LaunchedEffect(state.resolution, resultKind, state.error) {
        if (shouldEmitSmartCenterResultPresented(state.resolution != null)) {
            logger.log(
                DiagnosticLevel.INFO,
                "SMART_CENTER_RESULT_PRESENTED",
                "تم تجهيز نتيجة الرابط وعرضها للمستخدم",
                "ui.smart_center.result",
                mapOf(
                    "platform" to (state.result?.platform?.name ?: "unknown"),
                    "candidate_total" to candidates.size.toString(),
                    "deduplicated_candidate_total" to resultSet.all.size.toString(),
                    "available_total" to actionableOptions.size.toString(),
                    "best_overall" to (resultSet.bestOverall?.candidate?.id
                        ?.takeIf { it in actionableOptionIds } ?: "none"),
                    "best_quality" to (resultSet.bestQuality?.candidate?.id
                        ?.takeIf { it in actionableOptionIds } ?: "none"),
                    "smallest_size" to (smallestActionable?.candidate?.id ?: "none"),
                    "video_candidate_count" to rawVideoSourceCount.toString(),
                    "video_format_option_count" to availableVideoOptions.size.toString(),
                    "video_only_fallback_option_count" to videoOnlyFallbackOptions.size.toString(),
                    "unresolved_video_candidate_count" to unresolvedVideoSourceCount.toString(),
                    "known_video_resolution_count" to availableVideoOptions
                        .mapNotNull { it.candidate.format.height?.takeIf { height -> height > 0 } }
                        .distinct()
                        .size
                        .toString(),
                    "unknown_video_quality_count" to availableVideoOptions.count {
                        (it.candidate.format.height ?: 0) <= 0
                    }.toString(),
                    "known_audio_bitrate_tier_count" to resultSet.audio.count {
                        (it.candidate.format.bitrateKbps ?: 0) > 0
                    }.toString(),
                    "unknown_audio_bitrate_count" to resultSet.audio.count {
                        (it.candidate.format.bitrateKbps ?: 0) <= 0
                    }.toString(),
                    "video_audio_track_confirmed_count" to availableVideoOptions.count {
                        it.candidate.format.hasAudio
                    }.toString(),
                    "video_audio_track_unconfirmed_count" to primaryOptions.count {
                        it.candidate.format.kind == MediaKind.Video &&
                            it.candidate.format.hasVideo &&
                            !it.candidate.format.hasAudio
                    }.toString(),
                    "video_audio_companion_available_count" to availableVideoOptions.count {
                        !it.candidate.format.hasAudio && directAudioAvailable
                    }.toString(),
                    "audio_source_option_count" to resultSet.audio.count {
                        it.candidate.format.kind == MediaKind.Audio && it.candidate.format.hasAudio
                    }.toString(),
                    "audio_extraction_available" to audioExtractionAvailable.toString(),
                    "browser_observed_candidate_count" to candidates.count {
                        it.sourceContext.name == "BROWSER_OBSERVED"
                    }.toString(),
                ),
                null,
            )
        }
    }

    LaunchedEffect(state.error, state.resolution, state.result?.platform) {
        if (state.error != null && state.resolution == null) {
            logger.log(
                DiagnosticLevel.WARNING,
                "HOME_ERROR_PRESENTED",
                "تعذر تجهيز خيارات التنزيل وعُرضت رسالة الخطأ للمستخدم",
                "ui.home.error",
                mapOf("platform" to (state.result?.platform?.name ?: "unknown")),
                null,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AHDownload")
                        Text(
                            "تحميل ذكي وبسيط",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Surface(
                        modifier = Modifier.padding(start = 12.dp).size(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "AHDownload",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                actions = {
                    DiagnosticIconButton(
                        trackingScreen = "HOME",
                        trackingId = "HOME.browser_button",
                        trackingLabel = "فتح المتصفح الذكي",
                        disabledReason = "لا يوجد",
                        onClick = {
                            uiTraceLogger.interaction("HOME", "browser_button", "open_browser")
                            onOpenBrowser()
                        },
                    ) {
                        Icon(Icons.Rounded.Public, contentDescription = "المتصفح الذكي")
                    }
                    DiagnosticIconButton(
                        trackingScreen = "HOME",
                        trackingId = "HOME.iconbutton.02",
                        trackingLabel = "HOME.iconbutton.02",
                        disabledReason = "callsite_precondition_not_explicit",
                        onClick = {
                            uiTraceLogger.interaction("HOME", "settings_button", "open_settings")
                            onOpenSettings()
                        },
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "الإعدادات")
                    }
                },
            )
        },
        bottomBar = {
            AHBottomNavigationBar(
                trackingScreen = "HOME",
                selected = if (state.mode == HomeMode.Search) {
                    AHBottomNavDestination.YOUTUBE_SEARCH
                } else {
                    AHBottomNavDestination.HOME
                },
                activeDownloads = activeDownloads,
                onDestinationSelected = { destination ->
                    when (destination) {
                        AHBottomNavDestination.HOME -> {
                            uiTraceLogger.interaction("HOME", "bottom_nav_home", "open_home")
                            onModeChanged(HomeMode.Link)
                            onNavigateHome()
                        }
                        AHBottomNavDestination.YOUTUBE_SEARCH -> {
                            uiTraceLogger.interaction("HOME", "bottom_nav_youtube_search", "open_youtube_search")
                            onModeChanged(HomeMode.Search)
                            onOpenYouTubeSearch()
                        }
                        AHBottomNavDestination.DOWNLOADS -> {
                            uiTraceLogger.interaction("HOME", "bottom_nav_downloads", "open_downloads")
                            onOpenDownloads()
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!(showResultsScreen && state.mode == HomeMode.Link)) {
            item {
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        when (state.mode) {
                            HomeMode.Link -> "حمّل ما تحب"
                            HomeMode.Search -> "بحث YouTube"
                        },
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (state.mode) {
                            HomeMode.Link -> "الصق الرابط، اختر ما يناسبك، وابدأ التنزيل."
                            HomeMode.Search -> "ابحث في YouTube واختر الفيديو الذي تريد تنزيله."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.mode == HomeMode.Link) {
                item {
                    AHCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "رابط المحتوى",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedTextField(
                                value = state.url,
                                onValueChange = onUrlChanged,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "حقل رابط المحتوى" },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                                label = { Text("رابط الفيديو أو المحتوى") },
                                placeholder = { Text("الصق الرابط هنا أو استخدم زر اللصق") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DiagnosticOutlinedButton(
                                    trackingScreen = "HOME",
                                    trackingId = "HOME.outlinedbutton.paste_link",
                                    trackingLabel = "لصق الرابط",
                                    disabledReason = "التحليل أو استخراج المصدر جارٍ",
                                    enabled = !state.analyzing && !state.resolving,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        uiTraceLogger.interaction("HOME", "paste_button", "paste_clipboard")
                                        val clipboardManager =
                                            androidContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val pasted = clipboardManager?.primaryClip
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.coerceToText(androidContext)
                                            ?.toString()
                                            ?.trim()
                                            .orEmpty()
                                        uiTraceLogger.interaction(
                                            "HOME",
                                            "paste_button",
                                            "clipboard_read",
                                            mapOf(
                                                "text_present" to pasted.isNotBlank().toString(),
                                                "text_length" to pasted.length.toString(),
                                            ),
                                        )
                                        if (pasted.isNotBlank()) onPasteLink(pasted)
                                    },
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text("لصق الرابط")
                                }
                                DiagnosticOutlinedButton(
                                    trackingScreen = "HOME",
                                    trackingId = "HOME.outlinedbutton.clear_link",
                                    trackingLabel = "مسح الرابط",
                                    disabledReason = "الرابط فارغ أو التحليل جارٍ",
                                    enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        uiTraceLogger.interaction(
                                            "HOME",
                                            "clear_input_button",
                                            "clear_url",
                                            mapOf("previous_length" to state.url.length.toString()),
                                        )
                                        onUrlChanged("")
                                    },
                                ) {
                                    Icon(Icons.Rounded.Clear, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text("مسح")
                                }
                            }
                            Text(
                                "يمكنك لصق الرابط لبدء التحليل مباشرة، أو كتابته ثم الضغط على «تحليل الرابط».",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AHGradientPrimaryButton(
                                trackingScreen = "HOME",
                                trackingId = "HOME.gradient_primary.02",
                                text = when {
                                    state.analyzing || state.resolving -> "جارٍ تجهيز الخيارات..."
                                    else -> "تحليل الرابط"
                                },
                                enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                                onClick = {
                                    uiTraceLogger.interaction("HOME", "analyze_button", "analyze")
                                    onAnalyze()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "تحليل الرابط" },
                            )
                        }
                    }
                }

                if (
                    state.mode == HomeMode.Link &&
                    state.url.isBlank() &&
                    state.resolution == null &&
                    favoriteItems.isNotEmpty()
                ) {
                    item {
                        FavoriteLinksCard(
                            items = favoriteItems.take(4),
                            onSelect = onFavoriteSelected,
                        )
                    }
                }
            } else if (state.mode == HomeMode.Search) {
                item {
                    AHCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "ابحث عن فيديو أو قناة",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = onSearchQueryChanged,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "حقل البحث في YouTube" },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (state.searchQuery.isNotBlank()) {
                                        DiagnosticIconButton(
                                            trackingScreen = "HOME",
                                            trackingId = "HOME.iconbutton.05",
                                            trackingLabel = "HOME.iconbutton.05",
                                            disabledReason = "callsite_precondition_not_explicit",onClick = { onSearchQueryChanged("") }) {
                                            Icon(Icons.Rounded.Clear, contentDescription = "مسح البحث")
                                        }
                                    }
                                },
                                label = { Text("ابحث في YouTube") },
                                placeholder = { Text("عنوان فيديو أو اسم قناة...") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search,
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (state.searchQuery.isNotBlank() && !state.searching) {
                                            uiTraceLogger.interaction(
                                                "HOME",
                                                "search_keyboard_action",
                                                "search_youtube",
                                            )
                                            onSearch()
                                        }
                                    },
                                ),
                            )
                            DiagnosticButton(
                                trackingScreen = "HOME",
                                trackingId = "HOME.button.01",
                                trackingLabel = "بحث YouTube",
                                disabledReason = "عبارة البحث فارغة أو يوجد بحث جارٍ",
                                onClick = {
                                    uiTraceLogger.interaction("HOME", "search_button", "search_youtube")
                                    onSearch()
                                },
                                enabled = state.searchQuery.isNotBlank() && !state.searching,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (state.searching) "جاري البحث..." else "بحث")
                            }
                        }
                    }
                }

                if (state.searching) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp))
                        }
                    }
                }

                state.searchError?.let { error ->
                    item {
                        if (error == "لم نجد نتائج مطابقة حاليًا.") {
                            AHEmptyState(
                                title = "لم نجد نتائج مطابقة",
                                message = "لم تظهر نتائج لعبارة «${state.searchQuery}». يمكنك إعادة البحث أو تعديل العبارة.",
                                icon = Icons.Rounded.Search,
                                actionText = "إعادة البحث",
                                onAction = {
                                    uiTraceLogger.interaction("HOME", "search_empty_state", "retry_search")
                                    onSearch()
                                },
                                trackingScreen = "HOME",
                                trackingId = "HOME.search.empty_state.retry",
                            )
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            "تعذر تنفيذ البحث",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                                    DiagnosticOutlinedButton(
                                        trackingScreen = "HOME",
                                        trackingId = "HOME.outlinedbutton.01",
                                        trackingLabel = "إعادة البحث",
                                        disabledReason = "عبارة البحث فارغة أو يوجد بحث جارٍ",
                                        enabled = state.searchQuery.isNotBlank() && !state.searching,
                                        onClick = {
                                            uiTraceLogger.interaction("HOME", "search_retry_button", "retry_search")
                                            onSearch()
                                        },
                                    ) {
                                        Text("إعادة البحث")
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.searchResults.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "نتائج البحث",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        "${searchResultCounts.all} نتيجة",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (state.selectedSearchIds.isNotEmpty()) {
                                    DiagnosticTextButton(
                                        trackingScreen = "HOME",
                                        trackingId = "HOME.textbutton.01",
                                        trackingLabel = "مسح التحديد",
                                        disabledReason = "callsite_precondition_not_explicit",
                                        onClick = {
                                            uiTraceLogger.interaction("HOME", "search_selection", "clear_selection")
                                            onClearSearchSelection()
                                        },
                                    ) { Text("مسح التحديد") }
                                }
                            }
                            Text(
                                "نتائج «${state.searchQuery}» · حسب ترتيب YouTube",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "يعرض ${visibleSearchResults.size} من ${searchResultCounts.all} نتيجة",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                DiagnosticFilterChip(
                                    trackingScreen = "HOME",
                                    trackingId = "HOME.search_filter.all",
                                    trackingLabel = "كل النتائج",
                                    disabledReason = "لا توجد نتائج بحث متاحة",
                                    enabled = isYouTubeSearchFilterAvailable(YouTubeSearchFilter.All, searchResultCounts),
                                    selected = state.searchFilter == YouTubeSearchFilter.All,
                                    onClick = {
                                        uiTraceLogger.interaction("HOME", "search_filter", "all")
                                        onSearchFilterChanged(YouTubeSearchFilter.All)
                                    },
                                    label = { Text("الكل · ${searchResultCounts.all}") },
                                )
                            }
                            item {
                                DiagnosticFilterChip(
                                    trackingScreen = "HOME",
                                    trackingId = "HOME.search_filter.videos",
                                    trackingLabel = "الفيديوهات",
                                    disabledReason = "لا توجد فيديوهات عادية ضمن النتائج",
                                    enabled = isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Videos, searchResultCounts),
                                    selected = state.searchFilter == YouTubeSearchFilter.Videos,
                                    onClick = {
                                        uiTraceLogger.interaction("HOME", "search_filter", "videos")
                                        onSearchFilterChanged(YouTubeSearchFilter.Videos)
                                    },
                                    label = { Text("الفيديوهات · ${searchResultCounts.videos}") },
                                )
                            }
                            item {
                                DiagnosticFilterChip(
                                    trackingScreen = "HOME",
                                    trackingId = "HOME.search_filter.shorts",
                                    trackingLabel = "Shorts",
                                    disabledReason = "لا توجد نتائج Shorts ضمن النتائج الحالية",
                                    enabled = isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Shorts, searchResultCounts),
                                    selected = state.searchFilter == YouTubeSearchFilter.Shorts,
                                    onClick = {
                                        uiTraceLogger.interaction("HOME", "search_filter", "shorts")
                                        onSearchFilterChanged(YouTubeSearchFilter.Shorts)
                                    },
                                    label = { Text("Shorts · ${searchResultCounts.shorts}") },
                                )
                            }
                            item {
                                DiagnosticFilterChip(
                                    trackingScreen = "HOME",
                                    trackingId = "HOME.search_filter.channels",
                                    trackingLabel = "القنوات",
                                    disabledReason = "لا توجد قنوات ضمن النتائج الحالية",
                                    enabled = isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Channels, searchResultCounts),
                                    selected = state.searchFilter == YouTubeSearchFilter.Channels,
                                    onClick = {
                                        uiTraceLogger.interaction("HOME", "search_filter", "channels")
                                        onSearchFilterChanged(YouTubeSearchFilter.Channels)
                                    },
                                    label = { Text("القنوات · ${searchResultCounts.channels}") },
                                )
                            }
                        }
                    }
                    if (visibleSearchResults.isEmpty() && !state.searching) {
                        item {
                            Text(
                                "لا توجد نتائج ضمن هذا التصنيف. جرّب «الكل» أو غيّر عبارة البحث.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    if (state.selectedSearchIds.isNotEmpty() || state.batchDownloading || state.batchError != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = if (state.batchDownloading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (state.batchDownloading) {
                                        Text(
                                            "جاري تجهيز التنزيل الجماعي " + state.batchIndex + "/" + state.batchTotal,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        AHProgressBar(
                                            progress = if (state.batchTotal > 0) {
                                                state.batchIndex.toFloat() / state.batchTotal.toFloat()
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "تمت إضافة " + state.batchQueued + " عناصر إلى قائمة التنزيل.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else if (state.batchError != null) {
                                        Text(state.batchError, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    if (!state.batchDownloading && state.selectedSearchIds.isNotEmpty()) {
                                        DiagnosticButton(
                                            trackingScreen = "HOME",
                                            trackingId = "HOME.button.02",
                                            trackingLabel = "تنزيل ",
                                            disabledReason = "callsite_precondition_not_explicit",
                                            onClick = {
                                                uiTraceLogger.interaction(
                                                    "HOME",
                                                    "batch_download_button",
                                                    "start_batch_download",
                                                    mapOf("selected_count" to state.selectedSearchIds.size.toString()),
                                                )
                                                onBatchDownload(visibleSearchResults)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(Icons.Rounded.Download, contentDescription = null)
                                            Spacer(Modifier.size(6.dp))
                                            Text("تنزيل " + state.selectedSearchIds.size + " عناصر")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(
                        visibleSearchResults,
                        key = { "search-" + it.kind.name + "-" + it.id },
                    ) { item ->
                        SearchResultCard(
                            item = item,
                            canSelect = item.kind != ContentSearchKind.CHANNEL,
                            selected = item.kind != ContentSearchKind.CHANNEL &&
                                item.id in state.selectedSearchIds,
                            onToggleSelection = {
                                uiTraceLogger.interaction("HOME", "search_result_selection", "toggle")
                                onSearchSelectionToggle(item.id)
                            },
                            onClick = {
                                if (item.kind == ContentSearchKind.CHANNEL) {
                                    uiTraceLogger.interaction("HOME", "search_result", "open_channel")
                                    runCatching {
                                        androidContext.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(item.url)),
                                        )
                                    }.onFailure { error ->
                                        logger.log(
                                            DiagnosticLevel.WARNING,
                                            "SEARCH_CHANNEL_OPEN_FAILED",
                                            "تعذر فتح صفحة القناة",
                                            "home.search.channel",
                                            mapOf("channel_id" to item.id),
                                            error,
                                        )
                                    }
                                } else {
                                    uiTraceLogger.interaction(
                                        "HOME",
                                        "search_result",
                                        if (item.kind == ContentSearchKind.SHORT) "open_short" else "open_video",
                                    )
                                    onNavigateHome()
                                    onModeChanged(HomeMode.Link)
                                    onSearchResultSelected(item)
                                }
                            },
                        )
                    }
                }
            }

            if (state.analyzing || state.resolving) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                            Column {
                                Text(
                                    "نجهّز أفضل خيارات التنزيل",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "سيظهر الخيار الأول فور جاهزيته دون انتظار تفاصيل غير ضرورية.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            }

            if (showResultsScreen && state.mode == HomeMode.Link) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "نتائج التنزيل",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "اختر جودة الفيديو أو صيغة الصوت قبل بدء التنزيل.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showResultsScreen && mode == HomeMode.Link) state.resolution?.let { resolution ->
                item {
                    val audioOptions = resultSet.audio
                    UnifiedDownloadResultCard(
                        title = resolution.title ?: "محتوى الوسائط",
                        thumbnailUrl = resolution.thumbnailUrl,
                        durationMs = resolution.durationMs,
                        platform = state.result?.platform?.name,
                        kind = state.result?.kind,
                        favorite = currentFavorite,
                        uiTraceLogger = uiTraceLogger,
                        onToggleFavorite = {
                            uiTraceLogger.interaction(
                                "HOME",
                                "favorite_button",
                                "toggle_favorite_from_result",
                                mapOf("currently_favorite" to currentFavorite.toString()),
                            )
                            onToggleFavorite()
                        },
                        primaryOptions = primaryOptions,
                        sourceCandidates = candidates,
                        audioOptions = audioOptions,
                        onBackToLink = {
                            uiTraceLogger.interaction("HOME", "results_screen", "return_to_link")
                            showResultsScreen = false
                        },
                        selectedCandidateId = state.selectedCandidateId,
                        selectedAudioCandidateId = state.selectedAudioCandidateId,
                        validatingCandidateId = state.validatingCandidateId,
                        downloadQueued = state.downloadQueued,
                        errorMessage = state.error,
                        selectedAudioOutputFormat = state.selectedAudioOutputFormat,
                        onSelectAudioCandidate = {
                            uiTraceLogger.interaction(
                                "HOME",
                                "audio_source_option",
                                "select_audio_source_quality",
                                mapOf(
                                    "candidate_id" to it.candidate.id,
                                    "quality" to it.qualityLabel,
                                    "bitrate_kbps" to (it.candidate.format.bitrateKbps?.toString() ?: "unknown"),
                                ),
                            )
                            onSelectAudioCandidate(it.candidate.id)
                        },
                        onSelect = {
                            logSelection(
                                logger,
                                it,
                                state.selectedCandidateId,
                            )
                            uiTraceLogger.interaction(
                                "HOME",
                                "video_option",
                                "select_video_option",
                                mapOf(
                                    "candidate_id" to it.candidate.id,
                                    "quality" to it.qualityLabel,
                                    "group" to it.group.name,
                                ),
                            )
                            onSelectCandidate(it.candidate.id)
                        },
                        onDownload = {
                            uiTraceLogger.interaction(
                                "HOME",
                                "video_download_button",
                                "download_video",
                                mapOf("candidate_id" to it),
                            )
                            onDownloadCandidate(it)
                        },
                        onDownloadAudio = {
                            uiTraceLogger.interaction(
                                "HOME",
                                "audio_download_button",
                                "download_audio",
                                mapOf("candidate_id" to (it ?: "auto")),
                            )
                            onDownloadAudio(it)
                        },
                        onSelectAudioOutputFormat = {
                            uiTraceLogger.interaction(
                                "HOME",
                                "audio_format_option",
                                "select_audio_output_format",
                                mapOf("format" to it.name),
                            )
                            onSelectAudioOutputFormat(it)
                        },
                    )
                }
            }

            state.error?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    if (state.resolution != null) "تعذر بدء التنزيل" else "تعذر تجهيز الرابط",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            DiagnosticOutlinedButton(
                                trackingScreen = "HOME",
                                trackingId = "HOME.outlinedbutton.02",
                                trackingLabel = "المحاولة مرة أخرى",
                                disabledReason = "الرابط فارغ أو التحليل جارٍ",
                                onClick = {
                                    uiTraceLogger.interaction("HOME", "retry_button", "retry")
                                    onAnalyze()
                                },
                                enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("المحاولة مرة أخرى")
                            }
                        }
                    }
                }
            }

            if (state.downloadQueued) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "تمت إضافة التنزيل",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "يمكنك متابعة حالته من سجل التنزيلات.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            DiagnosticTextButton(
                                trackingScreen = "HOME",
                                trackingId = "HOME.textbutton.02",
                                trackingLabel = "فتح السجل",
                                disabledReason = "callsite_precondition_not_explicit",
                                onClick = {
                                    uiTraceLogger.interaction("HOME", "download_success", "open_downloads")
                                    onOpenDownloads()
                                },
                            ) {
                                Text("فتح السجل")
                            }
                        }
                    }
                }
            }

            if (!(showResultsScreen && state.mode == HomeMode.Link)) item {
                Text(
                    "يتم التحقق من المصدر عند بدء التنزيل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: ContentSearchItem,
    canSelect: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
) {
    val thumbnailShape = when (item.kind) {
        ContentSearchKind.VIDEO -> RoundedCornerShape(12.dp)
        ContentSearchKind.SHORT -> RoundedCornerShape(14.dp)
        ContentSearchKind.CHANNEL -> CircleShape
    }
    val thumbnailModifier = when (item.kind) {
        ContentSearchKind.VIDEO -> Modifier.size(width = 124.dp, height = 78.dp)
        ContentSearchKind.SHORT -> Modifier.size(width = 72.dp, height = 96.dp)
        ContentSearchKind.CHANNEL -> Modifier.size(60.dp)
    }
    val typeLabel = when (item.kind) {
        ContentSearchKind.VIDEO -> "فيديو"
        ContentSearchKind.SHORT -> "Shorts"
        ContentSearchKind.CHANNEL -> "قناة"
    }
    val cardShape = RoundedCornerShape(18.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .tracedClickable(
                trackingScreen = "HOME",
                trackingId = "HOME.search.result.${item.kind.name.lowercase()}.${homeTraceToken(item.id)}",
                trackingLabel = "search_result_${item.kind.name.lowercase()}",
                onClick = onClick,
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = thumbnailModifier.clip(thumbnailShape),
            ) {
                MediaThumbnail(
                    url = item.thumbnailUrl,
                    contentDescription = "الصورة المصغرة: " + item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.kind != ContentSearchKind.CHANNEL) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.78f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    item.durationLabel
                        ?.takeIf { it.isNotBlank() }
                        ?.let { duration ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp),
                                shape = RoundedCornerShape(5.dp),
                                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                            ) {
                                Text(
                                    duration,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                )
                            }
                        }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = when (item.kind) {
                            ContentSearchKind.VIDEO -> MaterialTheme.colorScheme.secondaryContainer
                            ContentSearchKind.SHORT -> MaterialTheme.colorScheme.tertiaryContainer
                            ContentSearchKind.CHANNEL -> MaterialTheme.colorScheme.primaryContainer
                        },
                    ) {
                        Text(
                            typeLabel,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (item.kind) {
                                ContentSearchKind.VIDEO -> MaterialTheme.colorScheme.onSecondaryContainer
                                ContentSearchKind.SHORT -> MaterialTheme.colorScheme.onTertiaryContainer
                                ContentSearchKind.CHANNEL -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        )
                    }
                    item.channelLabel?.takeIf { it.isNotBlank() }?.let { channel ->
                        Text(
                            channel,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canSelect) {
                DiagnosticFilterChip(
                    trackingScreen = "HOME",
                    trackingId = "HOME.search.selection.${item.kind.name.lowercase()}.${homeTraceToken(item.id)}",
                    trackingLabel = "search_result_selection",
                    disabledReason = "callsite_precondition_not_explicit",
                    selected = selected,
                    onClick = onToggleSelection,
                    label = { Text(if (selected) "محدد" else "تحديد") },
                )
            } else {
                Icon(
                    Icons.Rounded.Link,
                    contentDescription = "فتح القناة على YouTube",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FavoriteLinksCard(
    items: List<FavoriteItem>,
    onSelect: (FavoriteItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("المفضلة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "روابطك المحفوظة للعودة السريعة إليها.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEach { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tracedClickable(
                            trackingScreen = "HOME",
                            trackingId = "HOME.favorite.item.${homeTraceToken(item.id)}",
                            trackingLabel = "favorite_link_item",
                            onClick = { onSelect(item) },
                        ),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MediaThumbnail(
                            url = item.thumbnailUrl,
                            contentDescription = "الصورة المصغرة: " + (item.title ?: item.url),
                            modifier = Modifier
                                .size(width = 72.dp, height = 48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Text(
                            item.title ?: item.url,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun homeTraceToken(value: String): String =
    UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
        .toString()
        .replace("-", "")
        .take(16)

@Composable
private fun MediaThumbnail(
    url: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var imageLoadFailed by remember(url) { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        if (url.isNullOrBlank() || imageLoadFailed) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Image,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { imageLoadFailed = true },
            )
        }
    }
}

@Composable
private fun UnifiedDownloadResultCard(
    title: String,
    thumbnailUrl: String?,
    durationMs: Long?,
    platform: String?,
    kind: MediaKind?,
    uiTraceLogger: UiTraceLogger,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    primaryOptions: List<MediaPresentationModel>,
    audioOptions: List<MediaPresentationModel>,
    selectedCandidateId: String?,
    selectedAudioCandidateId: String?,
    validatingCandidateId: String?,
    selectedAudioOutputFormat: AudioOutputFormat?,
    onSelect: (MediaPresentationModel) -> Unit,
    onSelectAudioCandidate: (MediaPresentationModel) -> Unit,
    onSelectAudioOutputFormat: (AudioOutputFormat) -> Unit,
    onDownload: (String) -> Unit,
    onDownloadAudio: (String?) -> Unit,
    onBackToLink: () -> Unit,
    sourceCandidates: List<MediaCandidate>,
    downloadQueued: Boolean,
    errorMessage: String?,
) {
    val directAudioAvailable = audioOptions.any {
        it.candidate.format.kind == MediaKind.Audio && it.candidate.format.hasAudio
    }
    // A video-only representation is a valid "video + audio" choice only when
    // the resolver also supplied a direct audio track that can be merged at download time.
    val directVideoSourceAvailable = sourceCandidates.any {
        it.id == "direct" && it.format.kind == MediaKind.Video && it.format.hasVideo
    }
    val videoOptionGroups = classifyVideoPickerOptions(primaryOptions, directAudioAvailable)
    val allVideoOptions = videoOptionGroups.mainOptions
    val videoOnlyFallbackOptions = videoOptionGroups.videoOnlyFallback
    val allSelectableVideoOptions = videoOptionGroups.selectableOptions

    var showAllVideoOptions by remember(allVideoOptions) { mutableStateOf(false) }
    var showAudioSourceOptions by remember(title, thumbnailUrl, platform, durationMs) { mutableStateOf(false) }
    var selectionMode by remember(title, thumbnailUrl, platform, durationMs) { mutableStateOf<OutputSelectionMode?>(null) }

    val videoOptions = if (showAllVideoOptions) allVideoOptions else allVideoOptions.take(4)
    val muxedVideoAvailable = allVideoOptions.any { it.candidate.format.hasAudio }
    val audioAvailable = directAudioAvailable || muxedVideoAvailable || directVideoSourceAvailable
    val selectedVideo = allSelectableVideoOptions.firstOrNull { it.candidate.id == selectedCandidateId }
    val selectedAudioSource = audioOptions.firstOrNull { it.candidate.id == selectedAudioCandidateId }

    val hasRawVideoSource = sourceCandidates.any { it.format.kind == MediaKind.Video && it.format.hasVideo }
    val hasRawAudioSource = sourceCandidates.any { it.format.kind == MediaKind.Audio && it.format.hasAudio }
    // Keep empty sections visible when the source has been observed but could not be classified.
    val showVideoSection = allVideoOptions.isNotEmpty() || videoOnlyFallbackOptions.isNotEmpty() ||
        kind == MediaKind.Video || hasRawVideoSource
    val showAudioSection = audioAvailable || kind == MediaKind.Video || kind == MediaKind.Audio ||
        hasRawAudioSource || (kind == MediaKind.Unknown && hasRawVideoSource)
    val canDownload = validatingCandidateId == null && (
        selectionMode == OutputSelectionMode.VIDEO && selectedVideo != null ||
            selectionMode == OutputSelectionMode.AUDIO && selectedAudioOutputFormat != null
        )

    val sourceFingerprint = sourceCandidates.joinToString("|") { candidate ->
        candidate.id + ":" + candidate.format.id + ":" + candidate.format.kind.name + ":" +
            candidate.format.container.name + ":" + candidate.format.height + ":" +
            candidate.format.bitrateKbps + ":" + candidate.format.hasVideo + ":" + candidate.format.hasAudio
    } + "|" + primaryOptions.joinToString(",") { it.candidate.id } +
        "|" + videoOnlyFallbackOptions.joinToString(",") { it.candidate.id } +
        "|" + audioOptions.joinToString(",") { it.candidate.id }
    val resultGeneration = remember(title, platform, durationMs, kind, sourceFingerprint) {
        UUID.randomUUID().toString().take(8)
    }
    val resultCardTrace = remember(resultGeneration) {
        ResultCardTraceRecorder(uiTraceLogger, resultGeneration)
    }

    LaunchedEffect(resultGeneration) {
        resultCardTrace.recordSnapshot(
            platform = platform,
            kind = kind,
            title = title,
            hasThumbnail = !thumbnailUrl.isNullOrBlank(),
            durationMs = durationMs,
            rawCandidates = sourceCandidates,
            primaryOptions = primaryOptions,
            videoOptions = allSelectableVideoOptions,
            audioOptions = audioOptions,
            directAudioAvailable = directAudioAvailable,
            audioAvailable = audioAvailable,
            visibleVideoOptions = videoOptions.size + videoOnlyFallbackOptions.size,
            videoOptionsExpanded = showAllVideoOptions,
            selectedCandidateId = selectedCandidateId,
            selectedAudioCandidateId = selectedAudioCandidateId,
            selectedAudioOutputFormat = selectedAudioOutputFormat,
        )
    }

    LaunchedEffect(
        resultGeneration, selectionMode, selectedCandidateId, selectedAudioCandidateId,
        selectedAudioOutputFormat, validatingCandidateId, downloadQueued, errorMessage,
        showAllVideoOptions, favorite, canDownload, videoOptions.size, videoOnlyFallbackOptions.size,
    ) {
        resultCardTrace.recordState(
            selectionMode = selectionMode?.name ?: "NONE",
            selectedVideoId = selectedCandidateId,
            selectedAudioId = selectedAudioCandidateId,
            selectedAudioOutputFormat = selectedAudioOutputFormat?.name,
            selectedVideoQuality = selectedVideo?.qualityLabel,
            selectedAudioQuality = selectedAudioSource?.qualityLabel,
            validatingCandidateId = validatingCandidateId,
            downloadQueued = downloadQueued,
            favorite = favorite,
            videoOptionsExpanded = showAllVideoOptions,
            visibleVideoOptions = videoOptions.size + videoOnlyFallbackOptions.size,
            canDownload = canDownload,
            errorMessage = errorMessage,
        )
    }

    AHCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagnosticOutlinedButton(
                trackingScreen = "HOME",
                trackingId = "HOME.outlinedbutton.back_to_link",
                trackingLabel = "العودة إلى الرابط",
                disabledReason = "callsite_precondition_not_explicit",
                enabled = true,
                onClick = {
                    resultCardTrace.recordAction(
                        action = "return_to_link",
                        component = "result_card_back_button",
                        context = mapOf("result_generation" to resultGeneration),
                    )
                    onBackToLink()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("العودة إلى الرابط")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MediaThumbnail(
                    url = thumbnailUrl,
                    contentDescription = "الصورة المصغرة: " + title,
                    modifier = Modifier
                        .size(width = 104.dp, height = 74.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        platform?.let { AHStatusPill(platformLabel(it)) }
                        durationMs?.let { AHStatusPill(formatDuration(it)) }
                    }
                }

                DiagnosticIconButton(
                    trackingScreen = "HOME",
                    trackingId = "HOME.iconbutton.06",
                    trackingLabel = "HOME.iconbutton.06",
                    disabledReason = "callsite_precondition_not_explicit",
                    onClick = {
                        resultCardTrace.recordAction(
                            action = "toggle_favorite",
                            component = "favorite_button",
                            context = mapOf("previously_favorite" to favorite.toString()),
                        )
                        onToggleFavorite()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (favorite) "إزالة من المفضلة" else "إضافة إلى المفضلة"
                    },
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = if (favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    imageVector = when {
                        allVideoOptions.isNotEmpty() || directAudioAvailable -> Icons.Rounded.CheckCircle
                        videoOnlyFallbackOptions.isNotEmpty() -> Icons.Rounded.VideoFile
                        else -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (
                        allVideoOptions.isNotEmpty() ||
                        directAudioAvailable ||
                        videoOnlyFallbackOptions.isNotEmpty()
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        when {
                            allVideoOptions.isNotEmpty() || directAudioAvailable -> "خيارات التنزيل جاهزة"
                            videoOnlyFallbackOptions.isNotEmpty() -> "الفيديو متاح فقط"
                            audioAvailable -> "مصدر الصوت يحتاج تحققًا"
                            else -> "تعذر تأكيد خيارات التنزيل"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        buildList {
                            if (allVideoOptions.isNotEmpty()) add("${allVideoOptions.size} جودة فيديو")
                            else if (videoOnlyFallbackOptions.isNotEmpty()) add("${videoOnlyFallbackOptions.size} خيار فيديو فقط")
                            when {
                                directAudioAvailable || muxedVideoAvailable -> add("صيغ إخراج صوتية متاحة")
                                directVideoSourceAvailable -> add("استخراج الصوت يحتاج تحققًا")
                                showAudioSection -> add("لا يوجد مصدر صوت موثوق")
                            }
                        }.joinToString(" · ").ifBlank { "لا توجد خيارات مؤكدة حاليًا" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "اختر جودة الفيديو أو صيغة الصوت، ثم اضغط زر التنزيل. لن يبدأ التنزيل قبل اختيارك.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showVideoSection) {
                AHSectionHeader(
                    icon = Icons.Rounded.VideoFile,
                    title = when {
                        allVideoOptions.isNotEmpty() -> "الفيديو + الصوت"
                        videoOnlyFallbackOptions.isNotEmpty() -> "الفيديو"
                        else -> "الفيديو + الصوت"
                    },
                    subtitle = when {
                        allVideoOptions.isNotEmpty() && directAudioAvailable ->
                            "اختر الجودة؛ يُدمج الصوت المنفصل عند توفره."
                        allVideoOptions.isNotEmpty() ->
                            "اختر جودة الفيديو؛ راجع حالة الصوت لكل خيار."
                        videoOnlyFallbackOptions.isNotEmpty() ->
                            "هذه الخيارات تحفظ الفيديو فقط دون صوت."
                        else -> "اختر جودة الفيديو المناسبة."
                    },
                )

                if (videoOptions.isNotEmpty()) {
                    MediaFormatGrid(
                        options = videoOptions,
                        selected = if (selectionMode == OutputSelectionMode.VIDEO) selectedVideo else null,
                        validatingCandidateId = validatingCandidateId,
                        onSelect = {
                            resultCardTrace.recordAction(
                                action = "select_video_quality",
                                component = "video_quality_option",
                                context = mapOf(
                                    "candidate_id" to it.candidate.id,
                                    "quality" to it.qualityLabel,
                                    "container" to it.candidate.format.container.name,
                                    "height" to (it.candidate.format.height?.toString() ?: "unknown"),
                                    "has_audio" to it.candidate.format.hasAudio.toString(),
                                ),
                            )
                            selectionMode = OutputSelectionMode.VIDEO
                            onSelect(it)
                        },
                        audioOnly = false,
                        mergeAudioWhenMissing = directAudioAvailable,
                    )

                    if (!showAllVideoOptions && allVideoOptions.size > 4) {
                        DiagnosticTextButton(
                            trackingScreen = "HOME",
                            trackingId = "HOME.textbutton.04",
                            trackingLabel = "عرض المزيد من الصيغ والجودات",
                            disabledReason = "التحقق من المصدر جارٍ",
                            onClick = {
                                resultCardTrace.recordAction(
                                    action = "show_more_video_options",
                                    component = "video_options_more",
                                    context = mapOf("available_count" to allVideoOptions.size.toString()),
                                )
                                showAllVideoOptions = true
                            },
                            enabled = validatingCandidateId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("عرض المزيد من الصيغ والجودات")
                        }
                    }
                } else if (videoOnlyFallbackOptions.isEmpty()) {
                    val rawVideoSourceCount = primaryOptions.count {
                        it.candidate.format.kind == MediaKind.Video && it.candidate.format.hasVideo
                    }
                    UnifiedResultEmptyState(
                        if (rawVideoSourceCount > 0) {
                            "رُصد مصدر فيديو، لكن تعذّر تحديد صيغة قابلة للعرض. أعد التحليل بعد تحديث جلسة المنصة."
                        } else {
                            "لم يُعثر على مصدر فيديو قابل للتنزيل لهذا الرابط حاليًا."
                        },
                    )
                }
            }

            if (videoOnlyFallbackOptions.isNotEmpty()) {
                AHSectionHeader(
                    icon = Icons.Rounded.VideoFile,
                    title = "تنزيل الفيديو فقط",
                    subtitle = "لا يوجد مسار صوت موثوق لهذا الخيار. سيُنزّل التطبيق الفيديو الأصلي دون إضافة صوت.",
                )
                MediaFormatGrid(
                    options = videoOnlyFallbackOptions,
                    selected = if (selectionMode == OutputSelectionMode.VIDEO) selectedVideo else null,
                    validatingCandidateId = validatingCandidateId,
                    onSelect = {
                        resultCardTrace.recordAction(
                            action = "select_video_only_quality",
                            component = "video_only_quality_option",
                            context = mapOf(
                                "candidate_id" to it.candidate.id,
                                "quality" to it.qualityLabel,
                                "container" to it.candidate.format.container.name,
                                "height" to (it.candidate.format.height?.toString() ?: "unknown"),
                                "has_audio" to it.candidate.format.hasAudio.toString(),
                            ),
                        )
                        selectionMode = OutputSelectionMode.VIDEO
                        onSelect(it)
                    },
                    audioOnly = false,
                )
            }

            if (showVideoSection && showAudioSection) {
                ResultSectionDivider()
            }

            if (showAudioSection) {
                AHSectionHeader(
                    icon = Icons.Rounded.AudioFile,
                    title = "استخراج الصوت",
                    subtitle = when {
                        directAudioAvailable -> "صوت فقط · اختر الصيغة المطلوبة."
                        directVideoSourceAvailable -> "استخراج الصوت يحتاج إلى التحقق من المصدر."
                        else -> "اختر صيغة الصوت؛ يمكن تحديد المصدر يدويًا عند الحاجة."
                    },
                )

                if (directAudioAvailable || muxedVideoAvailable) {
                    Text(
                        "صيغة الملف الصوتي",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AudioOutputFormatGrid(
                        selected = if (selectionMode == OutputSelectionMode.AUDIO) selectedAudioOutputFormat else null,
                        enabled = validatingCandidateId == null,
                        onSelect = {
                            resultCardTrace.recordAction(
                                action = "select_audio_output_format",
                                component = "audio_output_format",
                                context = mapOf("output_format" to it.name, "output_label" to it.label),
                            )
                            selectionMode = OutputSelectionMode.AUDIO
                            onSelectAudioOutputFormat(it)
                        },
                    )
                    Text(
                        "سيُختار مصدر الصوت تلقائيًا ما لم تحدده يدويًا.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (audioOptions.isNotEmpty()) {
                        DiagnosticTextButton(
                            trackingScreen = "HOME",
                            trackingId = "HOME.textbutton.audio_source_toggle",
                            trackingLabel = "تحديد مصدر الصوت يدويًا",
                            disabledReason = "التحقق من المصدر جارٍ",
                            enabled = validatingCandidateId == null,
                            onClick = {
                                showAudioSourceOptions = !showAudioSourceOptions
                                resultCardTrace.recordAction(
                                    action = if (showAudioSourceOptions) "show_audio_source_options" else "hide_audio_source_options",
                                    component = "audio_source_options_toggle",
                                    context = mapOf("available_count" to audioOptions.size.toString()),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.AudioFile, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                if (showAudioSourceOptions) "إخفاء خيارات مصدر الصوت" else "تحديد مصدر الصوت يدويًا (اختياري)"
                            )
                            Spacer(Modifier.size(4.dp))
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                        }
                        if (showAudioSourceOptions) {
                            MediaFormatGrid(
                                options = audioOptions,
                                selected = if (selectionMode == OutputSelectionMode.AUDIO) selectedAudioSource else null,
                                validatingCandidateId = validatingCandidateId,
                                onSelect = {
                                    resultCardTrace.recordAction(
                                        action = "select_audio_source",
                                        component = "audio_source_option",
                                        context = mapOf(
                                            "candidate_id" to it.candidate.id,
                                            "quality" to it.qualityLabel,
                                            "bitrate_kbps" to (it.candidate.format.bitrateKbps?.toString() ?: "unknown"),
                                            "container" to it.candidate.format.container.name,
                                        ),
                                    )
                                    selectionMode = OutputSelectionMode.AUDIO
                                    onSelectAudioCandidate(it)
                                },
                                audioOnly = true,
                            )
                        }
                    }
                    Text(
                        "هذه الصيغ تنتج ملفًا صوتيًا فقط، ولا تغيّر جودة الفيديو.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    UnifiedResultEmptyState(
                        if (directVideoSourceAvailable) {
                            "رُصد ملف فيديو، لكن لم يُؤكد وجود مسار صوت صالح. لن نعرض خيارات استخراج الصوت قبل تأكيد المصدر."
                        } else {
                            "لم يُرصد مصدر صوت موثّق؛ لن تتاح صيغ استخراج الصوت حتى يكتمل تحليل المصدر."
                        },
                    )
                }
            }

            AHGradientPrimaryButton(
                trackingScreen = "HOME",
                trackingId = "HOME.gradient_primary.01",
                text = when {
                    validatingCandidateId != null -> "جارٍ تجهيز التنزيل..."
                    selectionMode == OutputSelectionMode.VIDEO && selectedVideo != null ->
                        "تنزيل " + buildQualityLine(selectedVideo) + when {
                            selectedVideo.candidate.format.hasAudio -> ""
                            directAudioAvailable -> " · دمج الصوت"
                            selectedVideo.candidate.id == "direct" -> " · ملف مباشر؛ الصوت غير مؤكد"
                            else -> " · فيديو فقط"
                        }
                    selectionMode == OutputSelectionMode.AUDIO && selectedAudioOutputFormat != null ->
                        "تنزيل " + audioOutputFormatButtonLabel(selectedAudioOutputFormat)
                    else -> "اختر خيارًا للتنزيل"
                },
                enabled = canDownload,
                onClick = {
                    resultCardTrace.recordAction(
                        action = "request_download",
                        component = "download_button",
                        context = mapOf(
                            "selection_mode" to (selectionMode?.name ?: "NONE"),
                            "candidate_id" to (
                                if (selectionMode == OutputSelectionMode.VIDEO) selectedVideo?.candidate?.id
                                else selectedAudioSource?.candidate?.id
                            ).orEmpty(),
                            "output_format" to (selectedAudioOutputFormat?.name ?: "not_audio"),
                            "can_download" to canDownload.toString(),
                        ),
                    )
                    when (selectionMode) {
                        OutputSelectionMode.VIDEO -> selectedVideo?.candidate?.id?.let(onDownload)
                        OutputSelectionMode.AUDIO -> onDownloadAudio(selectedAudioCandidateId)
                        null -> Unit
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = when (selectionMode) {
                            OutputSelectionMode.VIDEO -> when {
                                selectedVideo?.candidate?.format?.hasAudio == true ->
                                    "تنزيل الفيديو مع الصوت"
                                selectedVideo != null && directAudioAvailable ->
                                    "تنزيل الفيديو مع دمج مسار صوت منفصل"
                                selectedVideo?.candidate?.id == "direct" ->
                                    "تنزيل ملف فيديو مباشر؛ وجود مسار صوتي غير مؤكد"
                                selectedVideo != null ->
                                    "تنزيل الفيديو فقط؛ لم يتأكد توفر صوت للدمج"
                                else -> "اختر جودة الفيديو أولًا"
                            }
                            OutputSelectionMode.AUDIO -> selectedAudioOutputFormat
                                ?.let { "تنزيل الصوت بصيغة ${it.label}" }
                                ?: "اختر صيغة الصوت أولًا"
                            null -> "اختر جودة الفيديو أو صيغة الصوت أولًا"
                        }
                    },
            )

        }
    }
}

private enum class OutputSelectionMode {
    VIDEO,
    AUDIO,
}

@Composable
private fun AudioOutputFormatGrid(
    selected: AudioOutputFormat?,
    enabled: Boolean,
    onSelect: (AudioOutputFormat) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AudioOutputFormat.entries.chunked(2).forEach { rowFormats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowFormats.forEach { format ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .tracedClickable(
                                trackingScreen = "HOME",
                                trackingId = "HOME.audio.output_format.${homeTraceToken(format.name)}",
                                trackingLabel = "audio_output_format_option",enabled = enabled, onClick = { onSelect(format) })
                            .semantics {
                                contentDescription = if (format == selected) {
                                    "صيغة الإخراج محددة: " + format.label
                                } else {
                                    "اختيار صيغة الإخراج: " + format.label
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (format == selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    format.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (format == selected) FontWeight.Bold else FontWeight.SemiBold,
                                )
                                if (format == selected) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                            Text(
                                audioOutputFormatDescription(format),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (rowFormats.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun audioOutputFormatDescription(format: AudioOutputFormat): String = when (format) {
    AudioOutputFormat.Mp3 -> "ضغط شائع · 192 kbps"
    AudioOutputFormat.M4a -> "AAC · 192 kbps"
    AudioOutputFormat.Aac -> "AAC · 192 kbps"
    AudioOutputFormat.Opus -> "ضغط حديث · 128 kbps"
    AudioOutputFormat.Ogg -> "Vorbis · 192 kbps"
    AudioOutputFormat.Flac -> "بدون فقد · أكبر حجمًا"
    AudioOutputFormat.Wav -> "PCM · 16-bit · 44.1 kHz"
}

private fun audioOutputFormatButtonLabel(format: AudioOutputFormat): String = when (format) {
    AudioOutputFormat.Mp3 -> "MP3 · 192 kbps"
    AudioOutputFormat.M4a -> "M4A · AAC 192 kbps"
    AudioOutputFormat.Aac -> "AAC · 192 kbps"
    AudioOutputFormat.Opus -> "OPUS · 128 kbps"
    AudioOutputFormat.Ogg -> "OGG · Vorbis 192 kbps"
    AudioOutputFormat.Flac -> "FLAC · Lossless"
    AudioOutputFormat.Wav -> "WAV · PCM 16-bit"
}

@Composable
private fun UnifiedResultSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultSectionDivider() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    ) {}
}

@Composable
private fun UnifiedResultEmptyState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaFormatGrid(
    options: List<MediaPresentationModel>,
    selected: MediaPresentationModel?,
    validatingCandidateId: String?,
    onSelect: (MediaPresentationModel) -> Unit,
    audioOnly: Boolean,
    mergeAudioWhenMissing: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowOptions.forEach { option ->
                    MediaFormatOption(
                        model = option,
                        selected = option.candidate.id == selected?.candidate?.id,
                        enabled = validatingCandidateId == null,
                        onClick = { onSelect(option) },
                        audioOnly = audioOnly,
                        mergeAudioWhenMissing = mergeAudioWhenMissing,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (rowOptions.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MediaFormatOption(
    model: MediaPresentationModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    audioOnly: Boolean,
    mergeAudioWhenMissing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val format = model.candidate.format

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .tracedClickable(
                trackingScreen = "HOME",
                trackingId = "HOME.result.option.${homeTraceToken(model.candidate.id)}",
                trackingLabel = "media_result_option",enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (selected) {
                    "الخيار محدد: " + formatOptionAccessibilityLabel(model, audioOnly, mergeAudioWhenMissing)
                } else {
                    "اختيار: " + formatOptionAccessibilityLabel(model, audioOnly, mergeAudioWhenMissing)
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        formatOptionPrimaryLabel(model, audioOnly),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatOptionSecondaryLabel(model, audioOnly),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else if (model.recommendation != MediaResultRecommendation.None) {
                    Text(
                        "موصى به",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                formatOptionMetaLabel(model, audioOnly, mergeAudioWhenMissing),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun audioSectionSubtitle(
    audioOptions: List<MediaPresentationModel>,
    extractionSource: MediaPresentationModel?,
): String {
    val hasDirectAudio = audioOptions.any { it.candidate.format.kind == MediaKind.Audio }
    return when {
        hasDirectAudio ->
            "الصيغ المعروضة هي مسارات الصوت المتاحة فعليًا من المصدر"
        extractionSource != null ->
            "استخراج مسار الصوت الموجود داخل الفيديو؛ لا نعرض صيغة تحويل غير مدعومة"
        else ->
            "الصيغ الصوتية المتاحة فعليًا لهذا الرابط"
    }
}

private fun formatOptionPrimaryLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
): String {
    if (!audioOnly) {
        return model.candidate.format.height
            ?.takeIf { it > 0 }
            ?.let { "${it}p" }
            ?: model.qualityLabel.takeIf { it.isNotBlank() && !it.equals("Video", ignoreCase = true) }
            ?: "جودة غير معروفة"
    }

    return when (model.candidate.format.kind) {
        MediaKind.Audio -> model.candidate.format.bitrateKbps
            ?.takeIf { it > 0 }
            ?.let { "$it kbps" }
            ?: model.qualityLabel.takeIf { it.isNotBlank() && !it.equals("Audio", ignoreCase = true) }
            ?: containerLabel(model.candidate.format.container)
        MediaKind.Video -> "استخراج"
        else -> "صوت"
    }
}

private fun formatOptionSecondaryLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
): String {
    val format = model.candidate.format

    if (audioOnly) {
        return when (format.kind) {
            MediaKind.Audio -> buildList {
                normalizeCodecForUi(format.audioCodec)?.let(::add)
                containerLabel(format.container).takeIf { it != "صيغة غير معروفة" }?.let(::add)
            }.distinct().joinToString(" · ").ifBlank { "مسار صوتي مباشر" }

            MediaKind.Video -> buildList {
                add("من " + (format.height?.let { "${it}p" } ?: "الفيديو"))
                normalizeCodecForUi(format.audioCodec)?.let(::add)
            }.joinToString(" · ")

            else -> "مصدر صوتي"
        }
    }

    return buildList {
        add(containerLabel(format.container))
        normalizeCodecForUi(format.videoCodec)?.let(::add)
        normalizeCodecForUi(format.audioCodec)?.let(::add)
    }.distinct().joinToString(" · ")
}

private fun formatOptionMetaLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
    mergeAudioWhenMissing: Boolean = false,
): String {
    val format = model.candidate.format
    return if (audioOnly) {
        buildList {
            model.sizeLabel?.let(::add)
            model.fpsLabel?.let(::add)
        }.joinToString(" · ").ifBlank {
            when (format.kind) {
                MediaKind.Audio -> "جودة الصوت المتاحة"
                MediaKind.Video -> "استخراج الصوت من المصدر"
                else -> "صوت"
            }
        }
    } else {
        buildList {
            model.fpsLabel?.let(::add)
            model.sizeLabel?.let(::add)
            if (format.kind == MediaKind.Video) {
                add(
                    when {
                        format.hasAudio -> "صوت مدمج"
                        model.candidate.id == "direct" -> "ملف مباشر؛ وجود الصوت غير مؤكد"
                        mergeAudioWhenMissing -> "سيُدمج مسار الصوت المتاح عند التنزيل"
                        else -> "فيديو فقط · لا يوجد مسار صوت مؤكد"
                    },
                )
            }
        }.joinToString(" · ").ifBlank {
            when (format.kind) {
                MediaKind.Video -> if (format.hasAudio || mergeAudioWhenMissing) {
                    "صوت مدمج أو مسار صوت متاح"
                } else {
                    "فيديو فقط · لا يوجد مسار صوت مؤكد"
                }
                MediaKind.Audio -> "مسار صوت مباشر"
                else -> "نوع الوسائط غير محدد"
            }
        }
    }
}

private fun formatOptionAccessibilityLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
    mergeAudioWhenMissing: Boolean = false,
): String = buildList {
    add(formatOptionPrimaryLabel(model, audioOnly))
    formatOptionSecondaryLabel(model, audioOnly)
        .takeIf { it.isNotBlank() }
        ?.let(::add)
    formatOptionMetaLabel(model, audioOnly, mergeAudioWhenMissing)
        .takeIf { it.isNotBlank() }
        ?.let(::add)
}.joinToString(" · ")

private fun audioFormatLabel(model: MediaPresentationModel): String {
    val container = containerLabel(model.candidate.format.container)
    return if (container == "صيغة غير معروفة") {
        model.qualityLabel
    } else {
        container
    }
}

private fun buildQualityLine(model: MediaPresentationModel): String {
    val format = model.candidate.format
    return when (format.kind) {
        MediaKind.Video -> (format.height?.takeIf { it > 0 }?.let { "${it}p" } ?: "جودة غير معروفة") + " · " + containerLabel(format.container)
        MediaKind.Audio -> model.qualityLabel + " · " + containerLabel(format.container)
        else -> model.qualityLabel
    }
}

private fun normalizeCodecForUi(codec: String?): String? {
    val value = codec?.substringBefore(',')?.trim()?.lowercase() ?: return null
    return when {
        value.startsWith("avc") -> "H.264"
        value.startsWith("av01") -> "AV1"
        value.startsWith("vp9") -> "VP9"
        value.startsWith("vp8") -> "VP8"
        value.startsWith("mp4a") -> "AAC"
        value.startsWith("opus") -> "Opus"
        value.startsWith("vorbis") -> "Vorbis"
        else -> codec.substringBefore(',').trim().takeIf { it.isNotBlank() }
    }
}

private fun logSelection(
    logger: DiagnosticLogger,
    model: MediaPresentationModel,
    selectedId: String?,
) {
    logger.log(
        DiagnosticLevel.INFO,
        "SMART_CENTER_OPTION_SELECTED",
        "اختار المستخدم مصدرًا من النتائج",
        "ui.smart_center.selection",
        mapOf(
            "candidate_id" to model.candidate.id,
            "quality" to model.qualityLabel,
            "group" to model.group.name,
            "recommendation" to model.recommendation.name,
            "score" to model.score.toString(),
            "previous_selection" to (selectedId ?: "none"),
        ),
        null,
    )
}

private fun containerLabel(container: com.ahdownload.domain.resolver.MediaContainer): String =
    when (container) {
        com.ahdownload.domain.resolver.MediaContainer.Mp4 -> "MP4"
        com.ahdownload.domain.resolver.MediaContainer.Webm -> "WebM"
        com.ahdownload.domain.resolver.MediaContainer.Mkv -> "MKV"
        com.ahdownload.domain.resolver.MediaContainer.Mov -> "MOV"
        com.ahdownload.domain.resolver.MediaContainer.M4a -> "M4A"
        com.ahdownload.domain.resolver.MediaContainer.Mp3 -> "MP3"
        com.ahdownload.domain.resolver.MediaContainer.Aac -> "AAC"
        com.ahdownload.domain.resolver.MediaContainer.Ogg -> "OGG"
        com.ahdownload.domain.resolver.MediaContainer.Flac -> "FLAC"
        com.ahdownload.domain.resolver.MediaContainer.Wav -> "WAV"
        com.ahdownload.domain.resolver.MediaContainer.ThreeGp -> "3GP"
        com.ahdownload.domain.resolver.MediaContainer.Avi -> "AVI"
        com.ahdownload.domain.resolver.MediaContainer.Unknown -> "صيغة غير معروفة"
    }

private fun platformLabel(platform: String): String = when (platform) {
    "YouTube" -> "YouTube"
    "Instagram" -> "Instagram"
    "Facebook" -> "Facebook"
    "TikTok" -> "TikTok"
    "X" -> "X"
    "Pinterest" -> "Pinterest"
    "Reddit" -> "Reddit"
    "Twitch" -> "Twitch"
    "Vimeo" -> "Vimeo"
    "DirectMedia" -> "رابط مباشر"
    "Web" -> "موقع ويب"
    else -> platform
}

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Video -> "فيديو"
    MediaKind.Audio -> "صوت"
    MediaKind.Image -> "صورة"
    MediaKind.Unknown -> "محتوى"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return (totalSeconds / 60L).toString() + ":" + (totalSeconds % 60L).toString().padStart(2, '0')
}

/** Avoid reporting a result card for an error-only UI state. */
internal fun shouldEmitSmartCenterResultPresented(hasResolution: Boolean): Boolean = hasResolution
