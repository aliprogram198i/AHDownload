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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.ahdownload.core.designsystem.AHProgressBar
import androidx.compose.material3.MaterialTheme
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
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.favorites.FavoriteItem
import com.ahdownload.domain.favorites.FavoriteKey
import com.ahdownload.domain.favorites.FavoriteRepository
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
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
    onWatchSearchResult: (String) -> Unit = {},
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
        onWatchSearchResult = onWatchSearchResult,
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
    onWatchSearchResult: (String) -> Unit = {},
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
                        trackingId = "HOME.settings_button",
                        trackingLabel = "فتح الإعدادات",
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
                            onWatch = {
                                uiTraceLogger.interaction(
                                    "HOME",
                                    "search_result",
                                    "watch_inside_browser",
                                    mapOf("kind" to item.kind.name),
                                )
                                onWatchSearchResult(item.url)
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
    onWatch: () -> Unit,
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
    val cardShape = MaterialTheme.shapes.large

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
        if (item.kind != ContentSearchKind.CHANNEL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onWatch,
                    modifier = Modifier.semantics {
                        contentDescription = "مشاهدة داخل التطبيق"
                    },
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("مشاهدة داخل التطبيق")
                }
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

internal fun homeTraceToken(value: String): String =
    UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
        .toString()
        .replace("-", "")
        .take(16)

