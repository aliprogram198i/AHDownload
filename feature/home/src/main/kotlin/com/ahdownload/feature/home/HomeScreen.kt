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
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.favorites.FavoriteItem
import com.ahdownload.domain.favorites.FavoriteKey
import com.ahdownload.domain.favorites.FavoriteRepository
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
    logger: DiagnosticLogger,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onInitialUrlConsumed: () -> Unit,
    uiTraceLogger: UiTraceLogger,
    activeDownloads: Int = 0,
    preferencesProvider: DownloadPreferencesProvider,
    favoriteRepository: FavoriteRepository,
) {
    val context = LocalContext.current
    val favorites by favoriteRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteScope = rememberCoroutineScope()
    val factory = remember(onDownloadRequested, logger, context) {
        HomeViewModel.Factory(onDownloadRequested, logger, context, preferencesProvider)
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialUrl) {
        initialUrl?.takeIf { it.isNotBlank() }?.let {
            viewModel.onUrlChanged(it)
            onInitialUrlConsumed()
        }
    }

    HomeScreen(
        state = state,
        logger = logger,
        onUrlChanged = viewModel::onUrlChanged,
        onAnalyze = viewModel::analyze,
        onSelectCandidate = viewModel::selectCandidate,
        onDownloadCandidate = viewModel::downloadCandidate,
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
            viewModel.analyze()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: HomeUiState,
    logger: DiagnosticLogger,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onDownloadCandidate: (String) -> Unit,
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


    LaunchedEffect(
        state.url,
        state.analyzing,
        state.resolving,
        state.resolution?.title,
        candidates.size,
        state.selectedCandidateId,
        state.validatingCandidateId,
        state.error,
        state.downloadQueued,
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
                ";validating=" + (state.validatingCandidateId ?: "none") +
                ";error=" + (state.error != null) +
                ";show_all=" + state.showAll +
                ";filter=" + state.resultFilter.name +
                ";queued=" + state.downloadQueued,
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
                        "ألصق رابط الفيديو أو الصوت أو الملف، وسيتولى AHDownload اكتشاف أفضل المصادر والصيغ المتاحة.",
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
                            onClick = { onModeChanged(HomeMode.Link) },
                            label = { Text("رابط") },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.mode == HomeMode.Search,
                            onClick = {
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
                Card(modifier = Modifier.fillMaxWidth()) {
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
                                            val clipboard =
                                                androidContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val pasted = clipboard?.primaryClip
                                                ?.takeIf { it.itemCount > 0 }
                                                ?.getItemAt(0)
                                                ?.coerceToText(androidContext)
                                                ?.toString()
                                                ?.trim()
                                                .orEmpty()
                                            if (pasted.isNotBlank()) {
                                                
                                                onUrlChanged(pasted)
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "لصق الرابط")
                                    }
                                    if (state.url.isNotBlank()) {
                                        IconButton(
                                            enabled = !state.analyzing && !state.resolving,
                                            onClick = {
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
                        state.analyzing -> "جارٍ تحليل الرابط..."
                        state.resolving -> "جارٍ استخراج أفضل المصادر..."
                        else -> "تحليل الرابط"
                    },
                    enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                    onClick = {
                        uiTraceLogger.interaction("HOME", "analyze_button", "analyze")
                        onAnalyze()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "تحليل الرابط واستخراج الوسائط" },
                )
            }


            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
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
                                    onClick = onSearch,
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
                                TextButton(onClick = onClearSearchSelection) { Text("مسح التحديد") }
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
                                            onClick = { onBatchDownload(state.searchResults) },
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
                            onToggleSelection = { onSearchSelectionToggle(item.id) },
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
                                    if (state.analyzing) "1/2 · التحقق من الرابط" else "2/2 · استخراج أفضل المصادر",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "نختار مصادر صالحة ونرتبها حسب الجودة والحجم والتوافق.",
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
                        onSelect = onRecentLinkSelected,
                        onClear = onClearRecentLinks,
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
                    }.ifEmpty { resultSet.all }
                    val audioOptions = resultSet.audio
                    UnifiedDownloadResultCard(
                        title = resolution.title ?: "محتوى الوسائط",
                        thumbnailUrl = resolution.thumbnailUrl,
                        durationMs = resolution.durationMs,
                        platform = state.result?.platform?.name,
                        kind = state.result?.kind,
                        favorite = currentFavorite,
                        onToggleFavorite = onToggleFavorite,
                        primaryOptions = primaryOptions,
                        audioOptions = audioOptions,
                        selectedCandidateId = state.selectedCandidateId,
                        validatingCandidateId = state.validatingCandidateId,
                        onSelect = {
                            logSelection(
                                logger,
                                it,
                                state.selectedCandidateId,
                            )
                            onSelectCandidate(it.candidate.id)
                        },
                        onDownload = { onDownloadCandidate(it) },
                    )
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
                                    if (state.resolution != null) "تعذر بدء التنزيل" else "تعذر تجهيز المحتوى",
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
                                Text("إعادة المحاولة")
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
                            TextButton(onClick = onOpenDownloads) {
                                Text("فتح السجل")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "نتحقق من المصدر قبل بدء التنزيل.",
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
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    primaryOptions: List<MediaPresentationModel>,
    audioOptions: List<MediaPresentationModel>,
    selectedCandidateId: String?,
    validatingCandidateId: String?,
    onSelect: (MediaPresentationModel) -> Unit,
    onDownload: (String) -> Unit,
) {
    var optionsExpanded by remember { mutableStateOf(false) }
    val selected = primaryOptions.firstOrNull { it.candidate.id == selectedCandidateId }
        ?: primaryOptions.firstOrNull()
    val fallback = selected ?: primaryOptions.firstOrNull() ?: audioOptions.firstOrNull()
    val bestAudio = audioOptions.firstOrNull()
    val isVideo = kind == MediaKind.Video || fallback?.candidate?.format?.hasVideo == true
    val isAudio = kind == MediaKind.Audio || (!isVideo && fallback?.candidate?.format?.hasAudio == true)
    val actionLabel = when {
        isVideo -> "تحميل الفيديو"
        isAudio -> "تحميل الصوت"
        else -> "تحميل الملف"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaThumbnail(
                    url = thumbnailUrl,
                    contentDescription = "الصورة المصغرة: " + title,
                    modifier = Modifier
                        .size(width = 118.dp, height = 82.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        platform?.let {
                            item { AHStatusPill(platformLabel(it)) }
                        }
                        durationMs?.let {
                            item { AHStatusPill(formatDuration(it)) }
                        }
                        kind?.takeIf { it != MediaKind.Unknown }?.let {
                            item { AHStatusPill(kindLabel(it)) }
                        }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AHStatusPill("جاهز للتنزيل", success = true)
                fallback?.let { model ->
                    recommendationLabel(model)?.let { AHStatusPill(it) }
                    model.sizeLabel?.let { AHStatusPill(it) }
                }
            }

            fallback?.let { model ->
                Text(
                    "اختيار التنزيل",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        enabled = primaryOptions.isNotEmpty() && validatingCandidateId == null,
                        onClick = { optionsExpanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "اختيار جودة وصيغة التنزيل"
                            },
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(
                                unifiedOptionLabel(model),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            model.sizeLabel?.let {
                                Text(
                                    "الحجم: " + it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                    }

                    DropdownMenu(
                        expanded = optionsExpanded,
                        onDismissRequest = { optionsExpanded = false },
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 360.dp)
                            .heightIn(max = 360.dp),
                    ) {
                        primaryOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            unifiedOptionLabel(option),
                                            fontWeight = if (option.candidate.id == selectedCandidateId) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Medium
                                            },
                                        )
                                        Text(
                                            unifiedOptionDetail(option),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    optionsExpanded = false
                                    onSelect(option)
                                },
                            )
                        }
                    }
                }

                Button(
                    enabled = validatingCandidateId == null,
                    onClick = { onDownload(model.candidate.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = actionLabel + " " + (model.sizeLabel ?: "")
                        },
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (validatingCandidateId == model.candidate.id) {
                            "جارٍ التحقق من المصدر..."
                        } else {
                            actionLabel + (model.sizeLabel?.let { " · " + it } ?: "")
                        }
                    )
                }
            }

            if (bestAudio != null && !isAudio && fallback?.candidate?.id != bestAudio.candidate.id) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "🎵 الصوت",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            unifiedOptionLabel(bestAudio),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        enabled = validatingCandidateId == null,
                        onClick = { onDownload(bestAudio.candidate.id) },
                        modifier = Modifier.semantics {
                            contentDescription = "تحميل الصوت " + unifiedOptionLabel(bestAudio)
                        },
                    ) {
                        Icon(Icons.Rounded.AudioFile, contentDescription = null)
                        Spacer(Modifier.size(5.dp))
                        Text(
                            if (validatingCandidateId == bestAudio.candidate.id) {
                                "جارٍ التحقق..."
                            } else {
                                "تحميل الصوت"
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = primaryOptions.size > 1 && validatingCandidateId == null,
                    onClick = { optionsExpanded = true },
                ) {
                    Text(
                        if (primaryOptions.size > 1) {
                            "كل الصيغ (" + primaryOptions.size + ")"
                        } else {
                            "صيغة واحدة متاحة"
                        }
                    )
                }
            }
        }
    }
}

private fun unifiedOptionLabel(model: MediaPresentationModel): String {
    return buildQualityLine(model) + (model.sizeLabel?.let { " · " + it } ?: "")
}

private fun unifiedOptionDetail(model: MediaPresentationModel): String {
    val details = buildList {
        model.codecLabel?.let(::add)
        model.fpsLabel?.let(::add)
        add(kindPresentation(model))
    }
    return details.joinToString(" · ")
}

private fun recommendationLabel(model: MediaPresentationModel): String? =
    when (model.recommendation) {
        MediaResultRecommendation.BestOverall -> "الأفضل"
        MediaResultRecommendation.BestQuality -> "أفضل جودة"
        MediaResultRecommendation.SmallestSize -> "أصغر حجم"
        MediaResultRecommendation.None -> null
    }

private fun buildQualityLine(model: MediaPresentationModel): String {
    val format = model.candidate.format
    return when (format.kind) {
        MediaKind.Video -> model.qualityLabel + " · " + containerLabel(format.container)
        MediaKind.Audio -> model.qualityLabel + " · " + containerLabel(format.container)
        else -> model.qualityLabel
    }
}


private fun kindPresentation(model: MediaPresentationModel): String = when {
    model.candidate.format.hasVideo && model.candidate.format.hasAudio -> "فيديو + صوت"
    model.candidate.format.hasVideo -> "فيديو"
    model.candidate.format.hasAudio -> "صوت"
    model.candidate.format.kind == MediaKind.Image -> "صورة"
    else -> "ملف"
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
