package com.ahdownload.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.resolver.MediaResultGroup
import com.ahdownload.domain.resolver.MediaResultRecommendation
import com.ahdownload.domain.resolver.SmartResultEngine

@Composable
fun HomeRoute(
    initialUrl: String? = null,
    onDownloadRequested: suspend (MediaCandidate, String?, String?, String?) -> DownloadEnqueueResult,
    logger: DiagnosticLogger,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenUiDiagnostics: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val context = LocalContext.current
    val factory = remember(onDownloadRequested, logger, context) {
        HomeViewModel.Factory(onDownloadRequested, logger, context)
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
        onOpenSettings = onOpenSettings,
        onOpenDownloads = onOpenDownloads,
        onOpenUiDiagnostics = onOpenUiDiagnostics,
        onRecentLinkSelected = viewModel::selectRecentLink,
        onClearRecentLinks = viewModel::clearRecentLinks,
        uiTraceLogger = uiTraceLogger,
    )
}

private enum class ResultFilter(val label: String) {
    All("الكل"),
    Video("فيديو"),
    Audio("صوت"),
    Image("صور"),
    Other("ملفات"),
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
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenUiDiagnostics: () -> Unit,
    onInitialUrlConsumed: () -> Unit,
    onRecentLinkSelected: (RecentLink) -> Unit,
    onClearRecentLinks: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val androidContext = LocalContext.current
    val candidates = state.resolution?.candidates.orEmpty()
    val resultSet = remember(candidates) { SmartResultEngine().build(candidates) }
    var showAll by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ResultFilter.All) }
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
        showAll,
        filter,
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
                add("media_preview")
                add("recommendation_card")
                add("result_filters")
                add(if (showAll) "all_results" else "recommended_results")
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
                ";show_all=" + showAll +
                ";filter=" + filter.name +
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
                    "visible_total" to resultSet.visible.size.toString(),
                    "best_overall" to (resultSet.bestOverall?.candidate?.id ?: "none"),
                    "best_quality" to (resultSet.bestQuality?.candidate?.id ?: "none"),
                    "smallest_size" to (resultSet.smallestSize?.candidate?.id ?: "none"),
                ),
                null,
            )
        }
    }

    val filteredResults = remember(resultSet, filter, showAll) {
        when (filter) {
            ResultFilter.All -> if (showAll) resultSet.all else resultSet.visible
            ResultFilter.Video -> if (showAll) resultSet.video else resultSet.visible.filter { it.group == MediaResultGroup.Video }
            ResultFilter.Audio -> if (showAll) resultSet.audio else resultSet.visible.filter { it.group == MediaResultGroup.Audio }
            ResultFilter.Image -> if (showAll) resultSet.other.filter { it.candidate.format.kind == MediaKind.Image }
                else resultSet.visible.filter { it.candidate.format.kind == MediaKind.Image }
            ResultFilter.Other -> if (showAll) resultSet.other.filter { it.candidate.format.kind != MediaKind.Image }
                else resultSet.visible.filter { it.group == MediaResultGroup.Other }
        }
    }

    val counts = remember(resultSet) {
        mapOf(
            ResultFilter.All to resultSet.all.size,
            ResultFilter.Video to resultSet.video.size,
            ResultFilter.Audio to resultSet.audio.size,
            ResultFilter.Image to resultSet.other.count { it.candidate.format.kind == MediaKind.Image },
            ResultFilter.Other to resultSet.other.count { it.candidate.format.kind != MediaKind.Image },
        )
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
                .padding(padding)
                .padding(horizontal = 16.dp),
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
                                showAll = false
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
                                                showAll = false
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
                                                showAll = false
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
                                    if (state.analyzing) "نتحقق من الرابط" else "نستخرج الصيغ المتاحة",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "قد يستغرق ذلك لحظات حسب المصدر والشبكة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (
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

            state.result?.let { link ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AHStatusPill(platformLabel(link.platform.name))
                        if (link.kind != MediaKind.Unknown) {
                            AHStatusPill(kindLabel(link.kind))
                        }
                    }
                }
            }

            state.resolution?.let { resolution ->
                item {
                    MediaPreviewCard(
                        title = resolution.title ?: "محتوى الوسائط",
                        thumbnailUrl = resolution.thumbnailUrl,
                        durationMs = resolution.durationMs,
                        platform = state.result?.platform?.name,
                        kind = state.result?.kind,
                    )
                }

                resultSet.bestOverall?.let { best ->
                    item {
                        SmartHeroCard(
                            model = best,
                            selected = best.candidate.id == state.selectedCandidateId,
                            validating = best.candidate.id == state.validatingCandidateId,
                            onSelect = {
                                logSelection(logger, best, state.selectedCandidateId)
                                onSelectCandidate(best.candidate.id)
                            },
                            onDownload = { onDownloadCandidate(best.candidate.id) },
                        )
                    }
                }

                item {
                    ResultFilterRow(
                        selected = filter,
                        counts = counts,
                        showAll = showAll,
                        onSelect = { filter = it },
                        onToggleAll = { showAll = !showAll },
                    )
                }

                if (filteredResults.isNotEmpty()) {
                    item {
                        Text(
                            if (showAll) "جميع الصيغ المتاحة" else "الصيغ المقترحة",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    items(
                        filteredResults,
                        key = { "result-" + it.candidate.id },
                    ) { model ->
                        ResultOptionCard(
                            model = model,
                            selected = model.candidate.id == state.selectedCandidateId,
                            validating = model.candidate.id == state.validatingCandidateId,
                            onSelect = {
                                logSelection(logger, model, state.selectedCandidateId)
                                onSelectCandidate(model.candidate.id)
                            },
                            onDownload = { onDownloadCandidate(model.candidate.id) },
                        )
                    }
                } else {
                    item {
                        Text(
                            "لا توجد صيغ متاحة في هذا التصنيف.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                                    "تعذر إكمال العملية",
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
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            link.title ?: link.url,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            platformLabel(link.platform) + " · " + link.url,
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

@Composable
private fun MediaPreviewCard(
    title: String,
    thumbnailUrl: String?,
    durationMs: Long?,
    platform: String?,
    kind: MediaKind?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(
                url = thumbnailUrl,
                contentDescription = "الصورة المصغرة: " + title,
                modifier = Modifier
                    .size(width = 120.dp, height = 84.dp)
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
                    platform?.let { item { AssistChip(onClick = {}, label = { Text(platformLabel(it)) }) } }
                    durationMs?.let { item { AssistChip(onClick = {}, label = { Text(formatDuration(it)) }) } }
                }
                kind?.let {
                    Text(
                        kindLabel(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SmartHeroCard(
    model: MediaPresentationModel,
    selected: Boolean,
    validating: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !validating, onClick = onSelect),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AHStatusPill("موصى به", success = true)
                recommendationLabel(model)?.takeIf { it != "الأفضل" }?.let { AHStatusPill(it) }
                if (selected) AHStatusPill("محدد", success = true)
            }
            Text(
                "أفضل اختيار للتنزيل",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildQualityLine(model),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildDetailLine(model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                model.sizeLabel?.let { AHStatusPill(it) }
                model.fpsLabel?.let { AHStatusPill(it) }
                AHStatusPill(kindPresentation(model))
            }
            Button(
                enabled = !validating,
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "تنزيل أفضل اختيار" },
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (validating) "جارٍ التحقق من المصدر..." else "تنزيل الآن")
            }
        }
    }
}

@Composable
private fun ResultFilterRow(
    selected: ResultFilter,
    counts: Map<ResultFilter, Int>,
    showAll: Boolean,
    onSelect: (ResultFilter) -> Unit,
    onToggleAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الصيغ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultFilter.entries.forEach { item ->
                val count = counts[item] ?: 0
                if (count > 0 || item == ResultFilter.All) {
                    item {
                        FilterChip(
                            selected = item == selected,
                            onClick = { onSelect(item) },
                            label = { Text(item.label + " " + count) },
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onToggleAll,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showAll) "عرض الخيارات المقترحة فقط" else "عرض جميع الصيغ")
        }
    }
}

@Composable
private fun ResultOptionCard(
    model: MediaPresentationModel,
    selected: Boolean,
    validating: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val format = model.candidate.format
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !validating, onClick = onSelect),
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MediaTypeIcon(kind = format.kind, selected = selected)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        buildQualityLine(model),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildDetailLine(model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                model.sizeLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                recommendationLabel(model)?.let { AHStatusPill(it) }
                AHStatusPill(containerLabel(format.container))
                AHStatusPill(kindPresentation(model))
            }

            OutlinedButton(
                enabled = !validating,
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "تنزيل " + buildQualityLine(model) },
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(if (validating) "جارٍ التحقق..." else "تنزيل")
            }
        }
    }
}

@Composable
private fun MediaTypeIcon(kind: MediaKind, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (kind) {
                    MediaKind.Video -> Icons.Rounded.VideoFile
                    MediaKind.Audio -> Icons.Rounded.AudioFile
                    MediaKind.Image -> Icons.Rounded.Image
                    else -> Icons.Rounded.Folder
                },
                contentDescription = kindLabel(kind),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

private fun buildDetailLine(model: MediaPresentationModel): String {
    val format = model.candidate.format
    val parts = buildList {
        model.codecLabel?.let(::add)
        model.fpsLabel?.let(::add)
        when {
            format.hasVideo && format.hasAudio -> add("فيديو + صوت")
            format.hasVideo -> add("فيديو فقط")
            format.hasAudio -> add("صوت فقط")
            format.kind == MediaKind.Image -> add("صورة")
        }
        if (model.sizeLabel == null) add("الحجم يحدد أثناء التنزيل")
    }
    return parts.joinToString(" · ")
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
