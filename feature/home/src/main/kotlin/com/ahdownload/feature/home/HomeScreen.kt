package com.ahdownload.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.launch

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
    onCopyHomeTrace: () -> String = { "" },
    activeDownloads: Int = 0,
    preferencesProvider: DownloadPreferencesProvider,
    favoriteRepository: FavoriteRepository,
) {
    val context = LocalContext.current
    val favorites by favoriteRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteScope = rememberCoroutineScope()
    val factory = remember(onDownloadRequested, onAudioOnlyRequested, logger, context, preferencesProvider) {
        HomeViewModel.Factory(
            onDownloadRequested = onDownloadRequested,
            onAudioOnlyRequested = onAudioOnlyRequested,
            logger = logger,
            context = context,
            preferencesProvider = preferencesProvider,
        )
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialUrl) {
        initialUrl?.takeIf { it.isNotBlank() }?.let {
            uiTraceLogger.interaction(
                "HOME",
                "screen",
                "received_initial_url",
                mapOf("source" to "external_intent"),
            )
            viewModel.onUrlChanged(it)
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
        onSearch = viewModel::searchContent,
        onSearchResultSelected = viewModel::openSearchResult,
        onSearchSelectionToggle = viewModel::toggleSearchSelection,
        onClearSearchSelection = viewModel::clearSearchSelection,
        onBatchDownload = viewModel::downloadSelectedSearchResults,
        onOpenSettings = onOpenSettings,
        onOpenDownloads = onOpenDownloads,
        onRecentLinkSelected = viewModel::selectRecentLink,
        onClearRecentLinks = viewModel::clearRecentLinks,
        uiTraceLogger = uiTraceLogger,
        onCopyHomeTrace = onCopyHomeTrace,
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
            viewModel.onUrlChanged(item.url)
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
    onSearch: () -> Unit,
    onSearchResultSelected: (ContentSearchItem) -> Unit,
    onSearchSelectionToggle: (String) -> Unit,
    onClearSearchSelection: () -> Unit,
    onBatchDownload: (List<ContentSearchItem>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onRecentLinkSelected: (RecentLink) -> Unit,
    onClearRecentLinks: () -> Unit,
    uiTraceLogger: UiTraceLogger,
    onCopyHomeTrace: () -> String,
    activeDownloads: Int = 0,
    favoriteItems: List<FavoriteItem> = emptyList(),
    currentFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onFavoriteSelected: (FavoriteItem) -> Unit = {},
) {
    val mode = state.mode
    val androidContext = LocalContext.current
    val candidates = state.resolution?.candidates.orEmpty()
    val resultSet = remember(candidates) { SmartResultEngine().build(candidates) }
    val uiContext = rememberUiTraceContext()
    val clipboard = LocalClipboardManager.current

    DisposableEffect(Unit) {
        uiTraceLogger.interaction("HOME", "screen", "entered")
        onDispose {
            uiTraceLogger.interaction("HOME", "screen", "exited")
        }
    }

    LaunchedEffect(
        state.mode,
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
        state.searchError,
        state.selectedSearchIds.size,
        state.batchDownloading,
        state.batchIndex,
        state.batchTotal,
        state.batchQueued,
        state.batchError,
        state.recentLinks.size,
        favoriteItems.size,
        currentFavorite,
        activeDownloads,
    ) {
        val components = buildList {
            add("topbar")
            add("bottom_navigation")
            add("url_input")
            add("analyze_button")
            if (state.url.isNotBlank()) add("clear_input_button")
            add("paste_input_button")
            if (state.analyzing || state.resolving) add("loading_state")
            if (state.resolution != null) {
                add("unified_download_result")
            } else if (state.url.isBlank() && state.recentLinks.isNotEmpty()) {
                add("recent_links")
            }
            if (state.error != null) add("error_card")
            if (state.downloadQueued) add("download_success")
            if (state.searching) add("search_loading")
            if (state.searchResults.isNotEmpty()) add("search_results")
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

    LaunchedEffect(state.resolution?.title, candidates.size, state.error) {
        if (state.resolution != null || state.error != null) {
            logger.log(
                DiagnosticLevel.INFO,
                "SMART_CENTER_RESULT_PRESENTED",
                "تم تجهيز نتيجة الرابط وعرضها للمستخدم",
                "ui.smart_center.result",
                mapOf(
                    "platform" to (state.result?.platform?.name ?: "unknown"),
                    "candidate_total" to candidates.size.toString(),
                    "available_total" to resultSet.all.size.toString(),
                    "best_overall" to (resultSet.bestOverall?.candidate?.id ?: "none"),
                    "best_quality" to (resultSet.bestQuality?.candidate?.id ?: "none"),
                    "smallest_size" to (resultSet.smallestSize?.candidate?.id ?: "none"),
                    "video_format_option_count" to resultSet.video.size.toString(),
                    "known_video_resolution_count" to resultSet.video
                        .mapNotNull { it.candidate.format.height?.takeIf { height -> height > 0 } }
                        .distinct()
                        .size
                        .toString(),
                    "unknown_video_quality_count" to resultSet.video.count {
                        (it.candidate.format.height ?: 0) <= 0
                    }.toString(),
                    "known_audio_bitrate_tier_count" to resultSet.audio.count {
                        (it.candidate.format.bitrateKbps ?: 0) > 0
                    }.toString(),
                    "unknown_audio_bitrate_count" to resultSet.audio.count {
                        (it.candidate.format.bitrateKbps ?: 0) <= 0
                    }.toString(),
                    "video_audio_track_confirmed_count" to resultSet.video.count {
                        it.candidate.format.hasAudio
                    }.toString(),
                    "video_audio_track_unconfirmed_count" to resultSet.video.count {
                        !it.candidate.format.hasAudio
                    }.toString(),
                    "browser_observed_candidate_count" to candidates.count {
                        it.sourceContext.name == "BROWSER_OBSERVED"
                    }.toString(),
                ),
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
                    Icon(Icons.Rounded.Download, contentDescription = "AHDownload")
                },
                actions = {
                    IconButton(
                        onClick = {
                            uiTraceLogger.interaction("HOME", "copy_trace_button", "copy_home_trace")
                            clipboard.setText(
                                AnnotatedString(
                                    onCopyHomeTrace(),
                                ),
                            )
                        },
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ سجل الشاشة الرئيسية")
                    }
                    IconButton(
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
                selected = AHBottomNavDestination.HOME,
                activeDownloads = activeDownloads,
                onDestinationSelected = { destination ->
                    when (destination) {
                        AHBottomNavDestination.HOME -> Unit
                        AHBottomNavDestination.DOWNLOADS -> {
                            uiTraceLogger.interaction("HOME", "bottom_nav_downloads", "open_downloads")
                            onOpenDownloads()
                        }
                        AHBottomNavDestination.SETTINGS -> {
                            uiTraceLogger.interaction("HOME", "bottom_nav_settings", "open_settings")
                            onOpenSettings()
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
            item {
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        "حمّل أي رابط بسهولة",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "ألصق الرابط واحصل على خيارات التنزيل المناسبة بأسرع طريقة.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = state.mode == HomeMode.Link,
                            onClick = {
                                uiTraceLogger.interaction("HOME", "mode_link", "switch_to_link")
                                onModeChanged(HomeMode.Link)
                            },
                            label = { Text("رابط") },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.mode == HomeMode.Search,
                            onClick = {
                                uiTraceLogger.interaction("HOME", "mode_search", "switch_to_search")
                                onModeChanged(HomeMode.Search)
                            },
                            label = { Text("بحث YouTube") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
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

            if (state.mode == HomeMode.Link) {
            item {
                AHCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "الرابط",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = state.url,
                            onValueChange = {
                                                                onUrlChanged(it)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "حقل رابط المحتوى" },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            trailingIcon = {
                                Row {
                                    IconButton(
                                        enabled = !state.analyzing && !state.resolving,
                                        onClick = {
                                            uiTraceLogger.interaction("HOME", "paste_button", "paste_clipboard")
                                            val clipboard =
                                                androidContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val pasted = clipboard?.primaryClip
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
                                            if (pasted.isNotBlank()) {
                                                onPasteLink(pasted)
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "لصق الرابط")
                                    }
                                    if (state.url.isNotBlank()) {
                                        IconButton(
                                            enabled = !state.analyzing && !state.resolving,
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
                                            Icon(Icons.Rounded.Clear, contentDescription = "مسح الرابط")
                                        }
                                    }
                                }
                            },
                            label = { Text("رابط الفيديو أو المحتوى") },
                            placeholder = { Text("https://...") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Text(
                            "يدعم الروابط المباشرة والمنصات المدعومة تلقائيًا.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                AHGradientPrimaryButton(
                    text = when {
                        state.analyzing -> "جارٍ تجهيز الخيارات..."
                        state.resolving -> "جارٍ تجهيز الخيارات..."
                        else -> "الحصول على الخيارات"
                    },
                    enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                    onClick = {
                        uiTraceLogger.interaction("HOME", "analyze_button", "analyze")
                        onAnalyze()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "الحصول على خيارات التنزيل" },
                )
            }


            } else {
                item {
                    AHCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "ابحث عن فيديو",
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
                                        IconButton(onClick = { onSearchQueryChanged("") }) {
                                            Icon(Icons.Rounded.Clear, contentDescription = "مسح البحث")
                                        }
                                    }
                                },
                                label = { Text("ابحث في YouTube") },
                                placeholder = { Text("مثال: football highlights") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            )
                            Button(
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
                                Text(
                                    error,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                OutlinedButton(
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

                if (state.searchResults.isNotEmpty()) {
                    item {
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
                            if (state.selectedSearchIds.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        uiTraceLogger.interaction("HOME", "search_selection", "clear_selection")
                                        onClearSearchSelection()
                                    },
                                ) { Text("مسح التحديد") }
                            }
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
                                        LinearProgressIndicator(
                                            progress = {
                                                if (state.batchTotal > 0) state.batchIndex.toFloat() / state.batchTotal.toFloat() else 0f
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
                                        Button(
                                            onClick = {
                                                uiTraceLogger.interaction(
                                                    "HOME",
                                                    "batch_download_button",
                                                    "start_batch_download",
                                                    mapOf("selected_count" to state.selectedSearchIds.size.toString()),
                                                )
                                                onBatchDownload(state.searchResults)
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
                        state.searchResults,
                        key = { "search-" + it.id },
                    ) { item ->
                        SearchResultCard(
                            item = item,
                            selected = item.id in state.selectedSearchIds,
                            onToggleSelection = {
                                uiTraceLogger.interaction("HOME", "search_result_selection", "toggle")
                                onSearchSelectionToggle(item.id)
                            },
                            onClick = {
                                uiTraceLogger.interaction("HOME", "search_result", "open_video")
                                onModeChanged(HomeMode.Link)
                                onSearchResultSelected(item)
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

            if (
                state.mode == HomeMode.Link &&
                state.url.isBlank() &&
                state.resolution == null &&
                !state.analyzing &&
                !state.resolving &&
                state.recentLinks.isNotEmpty()
            ) {
                item {
                    RecentLinksCard(
                        links = state.recentLinks.take(4),
                        onSelect = {
                            uiTraceLogger.interaction("HOME", "recent_link", "select_recent")
                            onRecentLinkSelected(it)
                        },
                        onClear = {
                            uiTraceLogger.interaction("HOME", "recent_links", "clear_recent")
                            onClearRecentLinks()
                        },
                    )
                }
            }

            if (state.mode == HomeMode.Link) state.result?.let { link ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AHStatusPill(platformLabel(link.platform.name))
                        if (link.kind != MediaKind.Unknown) {
                            AHStatusPill(kindLabel(link.kind))
                        }
                    }
                }
            }

            if (mode == HomeMode.Link) state.resolution?.let { resolution ->
                item {
                    val resultKind = state.result?.kind ?: MediaKind.Unknown
                    val primaryOptions = when (resultKind) {
                        MediaKind.Video -> resultSet.video
                        MediaKind.Audio -> resultSet.audio
                        MediaKind.Image -> resultSet.other.filter { it.candidate.format.kind == MediaKind.Image }
                        MediaKind.Unknown -> resultSet.all
                    }
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
                        audioOptions = audioOptions,
                        selectedCandidateId = state.selectedCandidateId,
                        selectedAudioCandidateId = state.selectedAudioCandidateId,
                        validatingCandidateId = state.validatingCandidateId,
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
                            OutlinedButton(
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
                            TextButton(
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

            item {
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
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MediaThumbnail(
                url = item.thumbnailUrl,
                contentDescription = "الصورة المصغرة: " + item.title,
                modifier = Modifier
                    .size(width = 112.dp, height = 70.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                item.channelLabel?.let {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.durationLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.material3.FilterChip(
                selected = selected,
                onClick = onToggleSelection,
                label = { Text(if (selected) "محدد" else "تحديد") },
            )
        }
    }
}

@Composable
private fun RecentLinksCard(
    links: List<RecentLink>,
    onSelect: (RecentLink) -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("الروابط الأخيرة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "اضغط لإعادة تحليل رابط استخدمته مؤخرًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClear) { Text("مسح") }
            }
            links.forEach { link ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onSelect(link) }),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MediaThumbnail(
                            url = link.thumbnailUrl,
                            contentDescription = "صورة مصغرة: " + (link.title ?: link.url),
                            modifier = Modifier
                                .size(width = 72.dp, height = 48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                link.title ?: link.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                platformLabel(link.platform) + " · " + formatRelativeRecentTime(link.updatedAtEpochMs),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeRecentTime(epochMs: Long): String {
    val age = System.currentTimeMillis() - epochMs
    return when {
        age < 60_000L -> "الآن"
        age < 3_600_000L -> (age / 60_000L).toString() + " د"
        age < 86_400_000L -> (age / 3_600_000L).toString() + " س"
        else -> (age / 86_400_000L).toString() + " ي"
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
                        .clickable { onSelect(item) },
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

@Composable
private fun MediaThumbnail(
    url: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        if (url.isNullOrBlank()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Download,
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
) {
    val allVideoOptions = primaryOptions
        .filter {
            it.candidate.format.kind == MediaKind.Video &&
                it.candidate.format.hasVideo
        }
        .distinctBy { it.candidate.id }

    var showAllVideoOptions by remember(allVideoOptions) { mutableStateOf(false) }
    var selectionMode by remember(title, thumbnailUrl, platform, durationMs) { mutableStateOf<OutputSelectionMode?>(null) }

    val videoOptions = if (showAllVideoOptions) allVideoOptions else allVideoOptions.take(4)
    val directAudioAvailable = audioOptions.any {
        it.candidate.format.kind == MediaKind.Audio && it.candidate.format.hasAudio
    }
    val muxedVideoAvailable = allVideoOptions.any { it.candidate.format.hasAudio }
    val audioAvailable = directAudioAvailable || muxedVideoAvailable
    val selectedVideo = videoOptions.firstOrNull { it.candidate.id == selectedCandidateId }
    val selectedAudioSource = audioOptions.firstOrNull { it.candidate.id == selectedAudioCandidateId }

    val showVideoSection = allVideoOptions.isNotEmpty() || kind == MediaKind.Video
    val showAudioSection = audioAvailable || kind == MediaKind.Video || kind == MediaKind.Audio
    val canDownload = validatingCandidateId == null && (
        selectionMode == OutputSelectionMode.VIDEO && selectedVideo != null ||
            selectionMode == OutputSelectionMode.AUDIO && selectedAudioOutputFormat != null
        )

    AHCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MediaThumbnail(
                    url = thumbnailUrl,
                    contentDescription = "الصورة المصغرة: " + title,
                    modifier = Modifier
                        .size(width = 116.dp, height = 84.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        platform?.let { AHStatusPill(platformLabel(it), success = true) }
                        durationMs?.let { AHStatusPill(formatDuration(it)) }
                    }
                }

                IconButton(
                    onClick = onToggleFavorite,
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

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("النتيجة جاهزة", style = MaterialTheme.typography.labelLarge)
                        Text(
                            buildList {
                                val knownVideoQualities = allVideoOptions.count {
                                    (it.candidate.format.height ?: 0) > 0
                                }
                                val unknownVideoSources = allVideoOptions.size - knownVideoQualities
                                if (knownVideoQualities > 0) add("$knownVideoQualities خيار فيديو")
                                if (unknownVideoSources > 0) {
                                    add("$unknownVideoSources مصدر فيديو غير محدد الجودة")
                                }
                                if (audioAvailable) {
                                    add("${AudioOutputFormat.entries.size} صيغ إخراج صوت")
                                } else if (showAudioSection) {
                                    add("مصدر الصوت غير متاح")
                                }
                            }.joinToString(" · ").ifBlank { "خيارات متاحة" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showVideoSection) {
                AHSectionHeader(
                    icon = Icons.Rounded.VideoFile,
                    title = "استخراج الفيديو",
                    subtitle = "اختر الجودة والصيغة المتاحتين في المصدر؛ كل تركيبة جودة وصيغة تظهر مرة واحدة.",
                )

                if (videoOptions.isNotEmpty()) {
                    MediaFormatGrid(
                        options = videoOptions,
                        selected = selectedVideo,
                        validatingCandidateId = validatingCandidateId,
                        onSelect = {
                            selectionMode = OutputSelectionMode.VIDEO
                            onSelect(it)
                        },
                        audioOnly = false,
                    )

                    if (!showAllVideoOptions && allVideoOptions.size > 4) {
                        TextButton(
                            onClick = {
                                uiTraceLogger.interaction(
                                    "HOME",
                                    "video_options_more",
                                    "show_more_video_options",
                                    mapOf("available_count" to allVideoOptions.size.toString()),
                                )
                                showAllVideoOptions = true
                            },
                            enabled = validatingCandidateId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("عرض المزيد من الصيغ والجودات")
                        }
                    }
                } else {
                    UnifiedResultEmptyState("لا يتوفر مصدر فيديو قابل للتنزيل لهذا الرابط حاليًا.")
                }
            }

            if (showVideoSection && showAudioSection) {
                ResultSectionDivider()
            }

            if (showAudioSection) {
                AHSectionHeader(
                    icon = Icons.Rounded.AudioFile,
                    title = "استخراج الصوت",
                    subtitle = if (directAudioAvailable) {
                        "صوت فقط · اختر صيغة الإخراج التي تريدها."
                    } else {
                        "صوت فقط · سيُستخدم أفضل مصدر صوتي متاح في الخلفية."
                    },
                )

                if (audioAvailable) {
                    if (audioOptions.isNotEmpty()) {
                        Text(
                            "جودة وصيغة مصدر الصوت",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MediaFormatGrid(
                            options = audioOptions,
                            selected = selectedAudioSource,
                            validatingCandidateId = validatingCandidateId,
                            onSelect = {
                                selectionMode = OutputSelectionMode.AUDIO
                                onSelectAudioCandidate(it)
                            },
                            audioOnly = true,
                        )
                    }

                    Text(
                        "صيغة الملف الناتج",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AudioOutputFormatGrid(
                        selected = selectedAudioOutputFormat,
                        enabled = validatingCandidateId == null,
                        onSelect = {
                            selectionMode = OutputSelectionMode.AUDIO
                            onSelectAudioOutputFormat(it)
                        },
                    )

                    Text(
                        "MP3 وM4A وAAC وOPUS وغيرها هنا تعني ملفًا صوتيًا فقط، وليست جودة فيديو.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    UnifiedResultEmptyState("لا يوجد مصدر صوتي صالح لهذا الرابط حاليًا.")
                }
            }

            AHGradientPrimaryButton(
                text = when {
                    validatingCandidateId != null -> "جارٍ تجهيز التنزيل..."
                    selectionMode == OutputSelectionMode.VIDEO && selectedVideo != null ->
                        "تنزيل " + buildQualityLine(selectedVideo) + when {
                            selectedVideo.candidate.format.hasAudio -> ""
                            directAudioAvailable -> " · دمج الصوت"
                            else -> " · فيديو فقط"
                        }
                    selectionMode == OutputSelectionMode.AUDIO && selectedAudioOutputFormat != null ->
                        "تنزيل " + audioOutputFormatButtonLabel(selectedAudioOutputFormat)
                    else -> "اختر خيارًا للتنزيل"
                },
                enabled = canDownload,
                onClick = {
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
                            .clickable(enabled = enabled, onClick = { onSelect(format) })
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
    modifier: Modifier = Modifier,
) {
    val format = model.candidate.format

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (selected) {
                    "الخيار محدد: " + formatOptionAccessibilityLabel(model, audioOnly)
                } else {
                    "اختيار: " + formatOptionAccessibilityLabel(model, audioOnly)
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
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
                formatOptionMetaLabel(model, audioOnly),
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
        MediaKind.Audio -> containerLabel(model.candidate.format.container)
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
                model.qualityLabel
                    .takeIf { it.isNotBlank() && !it.equals("Audio", ignoreCase = true) }
                    ?.let(::add)
            }.joinToString(" · ").ifBlank { "مسار صوتي مباشر" }

            MediaKind.Video -> buildList {
                add("من " + (format.height?.let { "${it}p" } ?: "الفيديو"))
                normalizeCodecForUi(format.audioCodec)?.let(::add)
            }.joinToString(" · ")

            else -> "مصدر صوتي"
        }
    }

    return containerLabel(format.container)
}

private fun formatOptionMetaLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
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
                add(if (format.hasAudio) "صوت مدمج" else "لا يوجد صوت مدمج مؤكد")
            }
        }.joinToString(" · ").ifBlank {
            when (format.kind) {
                MediaKind.Video -> "لا يوجد صوت مدمج مؤكد"
                MediaKind.Audio -> "مسار صوت مباشر"
                else -> "نوع الوسائط غير محدد"
            }
        }
    }
}

private fun formatOptionAccessibilityLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
): String = buildList {
    add(formatOptionPrimaryLabel(model, audioOnly))
    formatOptionSecondaryLabel(model, audioOnly)
        .takeIf { it.isNotBlank() }
        ?.let(::add)
    formatOptionMetaLabel(model, audioOnly)
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
