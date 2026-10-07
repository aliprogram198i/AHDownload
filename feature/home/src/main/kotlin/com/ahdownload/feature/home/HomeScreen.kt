package com.ahdownload.feature.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.background
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.resolver.MediaResultGroup
import com.ahdownload.domain.resolver.MediaResultRecommendation
import com.ahdownload.domain.resolver.SmartResultEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun HomeRoute(
    initialUrl: String? = null,
    onDownloadRequested: suspend (MediaCandidate, String?, String?) -> com.ahdownload.domain.download.DownloadEnqueueResult,
    logger: DiagnosticLogger,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val context = LocalContext.current
    val factory = remember(onDownloadRequested, logger, context) {
        HomeViewModel.Factory(onDownloadRequested, logger, context)
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialUrl) {
        initialUrl?.takeIf { it.isNotBlank() }?.let(viewModel::onUrlChanged)
    }

    HomeScreen(
        state = state,
        logger = logger,
        onUrlChanged = viewModel::onUrlChanged,
        onAnalyze = viewModel::analyze,
        onSelectCandidate = viewModel::selectCandidate,
        onDownload = viewModel::downloadSelected,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenSettings = onOpenSettings,
        onOpenDownloads = onOpenDownloads,
        uiTraceLogger = uiTraceLogger,
    )
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    logger: DiagnosticLogger,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onDownload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val androidContext = LocalContext.current
    val candidates = state.resolution?.candidates.orEmpty()
    val engine = remember { SmartResultEngine() }
    val resultSet = remember(candidates) { engine.build(candidates) }
    var showAll by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(MediaResultGroup.Video) }

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(
        state.url, state.analyzing, state.resolving,
        state.resolution?.title, state.resolution?.thumbnailUrl,
        candidates.size, state.selectedCandidateId,
        state.validatingCandidateId, state.error, showAll, filter,
    ) {
        val components = buildList {
            add("topbar")
            add("bottom_navigation")
            add("url_input")
            add("paste_button")
            if (state.url.isNotBlank()) add("clear_button")
            add("analyze_button")
            if (state.analyzing || state.resolving) add("loading_state")
            if (state.resolution != null) {
                add("media_preview")
                add("recommended_result")
                add("result_filters")
                add(if (showAll) "all_options" else "recommended_options")
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
                ";filter=" + filter.name,
            context = uiContext,
        )
    }

    LaunchedEffect(
        state.resolution?.title,
        candidates.size,
        state.selectedCandidateId,
        state.validatingCandidateId,
        state.error,
    ) {
        if (state.resolution != null || state.error != null) {
            logger.log(
                DiagnosticLevel.INFO,
                "SMART_CENTER_RESULT_PRESENTED",
                "تم تجهيز نتائج الرابط وعرضها",
                "ui.smart_center.result",
                mapOf(
                    "platform" to (state.result?.platform?.name ?: "unknown"),
                    "candidate_total" to candidates.size.toString(),
                    "normalized_total" to resultSet.all.size.toString(),
                    "visible_total" to resultSet.visible.size.toString(),
                    "hidden_total" to resultSet.hiddenCount.toString(),
                    "best_overall" to (resultSet.bestOverall?.candidate?.id ?: "none"),
                    "best_quality" to (resultSet.bestQuality?.candidate?.id ?: "none"),
                    "smallest_size" to (resultSet.smallestSize?.candidate?.id ?: "none"),
                    "presentation_policy" to "normalized;deduplicated;ranked;grouped",
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
                        Text("AHDownload", fontWeight = FontWeight.Bold)
                        if (state.resolution == null && state.error == null) {
                            Text(
                                "مركز التحميل الذكي",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null)
                },
                actions = {
                    IconButton(onClick = {
                        uiTraceLogger.interaction("HOME", "topbar_settings", "open_settings")
                        onOpenSettings()
                    }) {
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "حمّل الفيديو أو الصوت أو الملف من الرابط",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "الصق الرابط وسنكتشف أفضل صيغة حقيقية ومتوافقة قبل بدء التنزيل.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = state.url,
                            onValueChange = {
                                showAll = false
                                onUrlChanged(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Rounded.Link, contentDescription = null)
                            },
                            trailingIcon = {
                                if (state.url.isNotBlank()) {
                                    IconButton(
                                        enabled = !state.analyzing && !state.resolving,
                                        onClick = {
                                            uiTraceLogger.interaction("HOME", "clear_button", "clear_url")
                                            showAll = false
                                            onUrlChanged("")
                                        },
                                    ) {
                                        Icon(Icons.Rounded.Clear, contentDescription = "مسح الرابط")
                                    }
                                }
                            },
                            label = { Text("رابط المحتوى") },
                            placeholder = { Text("YouTube أو Instagram أو رابط مباشر") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (state.url.isNotBlank() && !state.analyzing && !state.resolving) {
                                        onAnalyze()
                                    }
                                },
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                enabled = !state.analyzing && !state.resolving,
                                onClick = {
                                    val clipboard =
                                        androidContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as? android.content.ClipboardManager
                                    val text = clipboard?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(androidContext)
                                        ?.toString()
                                        ?.trim()
                                        .orEmpty()
                                    if (text.isNotBlank()) {
                                        uiTraceLogger.interaction("HOME", "paste_button", "paste_url")
                                        showAll = false
                                        onUrlChanged(text)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("لصق")
                            }
                            OutlinedButton(
                                enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                                onClick = onAnalyze,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("تحليل")
                            }
                        }
                    }
                }
            }

            item {
                AHGradientPrimaryButton(
                    text = when {
                        state.analyzing -> "جارٍ تحليل الرابط..."
                        state.resolving -> "جارٍ استخراج الوسائط..."
                        else -> "تحليل الرابط واستخراج الوسائط"
                    },
                    enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.analyzing || state.resolving) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Column {
                                Text(
                                    if (state.analyzing) "نحلل الرابط" else "نستخرج أفضل المصادر",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "قد يستغرق ذلك لحظات حسب المنصة والمصدر.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            state.resolution?.let { resolution ->
                item {
                    MediaPreviewCard(
                        title = resolution.title ?: "محتوى الوسائط",
                        thumbnailUrl = resolution.thumbnailUrl,
                        platform = state.result?.platform?.name,
                        kind = state.result?.kind,
                        durationMs = resolution.durationMs,
                        total = resultSet.all.size,
                        video = resultSet.video.size,
                        audio = resultSet.audio.size,
                        other = resultSet.other.size,
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
                            onDownload = onDownload,
                        )
                    }
                }

                item {
                    ResultFilters(
                        filter = filter,
                        showAll = showAll,
                        videoCount = resultSet.video.size,
                        audioCount = resultSet.audio.size,
                        otherCount = resultSet.other.size,
                        onFilterSelected = {
                            filter = it
                            showAll = false
                        },
                        onShowAll = { showAll = !showAll },
                    )
                }

                val sourceList = when (filter) {
                    MediaResultGroup.Video -> resultSet.video
                    MediaResultGroup.Audio -> resultSet.audio
                    MediaResultGroup.Other -> resultSet.other
                }
                val filtered = (if (showAll) sourceList else resultSet.visible.filter { it.group == filter })
                    .filterNot { it.candidate.id == resultSet.bestOverall?.candidate?.id }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                if (showAll) "كل الخيارات المتاحة" else "الصيغ المقترحة",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${filtered.size} خيار ظاهر",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showAll && resultSet.hiddenCount > 0) {
                            Text(
                                "مخفي سابقًا: ${resultSet.hiddenCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (filtered.isNotEmpty()) {
                    items(filtered, key = { "option-" + it.candidate.id }) { model ->
                        ResultOptionCard(
                            model = model,
                            selected = model.candidate.id == state.selectedCandidateId,
                            validating = model.candidate.id == state.validatingCandidateId,
                            onSelect = {
                                logSelection(logger, model, state.selectedCandidateId)
                                onSelectCandidate(model.candidate.id)
                            },
                            onDownload = onDownload,
                        )
                    }
                } else {
                    item {
                        EmptyResultsState(
                            when (filter) {
                                MediaResultGroup.Video -> "لا توجد صيغ فيديو إضافية متاحة."
                                MediaResultGroup.Audio -> "لا توجد صيغ صوت إضافية متاحة."
                                MediaResultGroup.Other -> "لا توجد صور أو ملفات إضافية متاحة."
                            },
                        )
                    }
                }
            }

            state.error?.let { error ->
                item {
                    ErrorCard(
                        message = error,
                        onRetry = onAnalyze,
                        onOpenDiagnostics = onOpenDiagnostics,
                        canRetry = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                    )
                }
            }

            if (state.downloadQueued) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column {
                                Text("تمت إضافة التنزيل", fontWeight = FontWeight.Bold)
                                Text(
                                    "يمكنك متابعة الحالة من سجل التنزيلات.",
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
}

@Composable
private fun MediaPreviewCard(
    title: String,
    thumbnailUrl: String?,
    platform: String?,
    kind: MediaKind?,
    durationMs: Long?,
    total: Int,
    video: Int,
    audio: Int,
    other: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteThumbnail(
                url = thumbnailUrl,
                modifier = Modifier.fillMaxWidth().height(210.dp),
                cornerRadius = 16.dp,
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    platform?.takeIf { it != "Unknown" }?.let { AHStatusPill(it) }
                    kind?.takeIf { it != MediaKind.Unknown }?.let { AHStatusPill(mediaKindLabel(it)) }
                    durationMs?.let { AHStatusPill(formatDuration(it)) }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AHStatusPill("${total} صيغة")
                    if (video > 0) AHStatusPill("${video} فيديو")
                    if (audio > 0) AHStatusPill("${audio} صوت")
                    if (other > 0) AHStatusPill("${other} إضافية")
                }
            }
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !validating, onClick = onSelect),
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
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "موصى به",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                recommendationLabel(model)?.takeUnless { it == "الأفضل" }?.let { AHStatusPill(it) }
                Spacer(Modifier.weight(1f))
                if (selected) AHStatusPill("محدد", success = true)
            }

            Text(
                "أفضل توازن للتنزيل",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildQualityLine(model),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildDetailLine(model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                model.sizeLabel?.let { AHStatusPill(it) }
                model.fpsLabel?.let { AHStatusPill(it) }
                AHStatusPill(mediaKindShortLabel(model.candidate.format))
            }

            if (!selected) {
                OutlinedButton(
                    enabled = !validating,
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("اختيار هذا الاقتراح")
                }
            } else {
                Button(
                    enabled = !validating,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (validating) "جارٍ التحقق من المصدر..." else "تنزيل الآن")
                }
            }
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !validating, onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    model.qualityLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                AHStatusPill(containerLabel(format.container))
                recommendationLabel(model)?.let { AHStatusPill(it) }
                Spacer(Modifier.weight(1f))
                if (selected) AHStatusPill("محدد", success = true)
            }
            Text(
                buildDetailLine(model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                model.sizeLabel?.let { AHStatusPill(it) }
                AHStatusPill(mediaKindShortLabel(format))
                model.fpsLabel?.let { AHStatusPill(it) }
            }
            if (selected) {
                Button(
                    enabled = !validating,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (validating) "جارٍ التحقق..." else "تنزيل")
                }
            } else {
                OutlinedButton(
                    enabled = !validating,
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("اختيار هذه الصيغة")
                }
            }
        }
    }
}

@Composable
private fun ResultFilters(
    filter: MediaResultGroup,
    showAll: Boolean,
    videoCount: Int,
    audioCount: Int,
    otherCount: Int,
    onFilterSelected: (MediaResultGroup) -> Unit,
    onShowAll: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = filter == MediaResultGroup.Video && !showAll,
                onClick = { onFilterSelected(MediaResultGroup.Video) },
                label = { Text("فيديو $videoCount") },
            )
        }
        item {
            FilterChip(
                selected = filter == MediaResultGroup.Audio && !showAll,
                onClick = { onFilterSelected(MediaResultGroup.Audio) },
                label = { Text("صوت $audioCount") },
            )
        }
        if (otherCount > 0) {
            item {
                FilterChip(
                    selected = filter == MediaResultGroup.Other && !showAll,
                    onClick = { onFilterSelected(MediaResultGroup.Other) },
                    label = { Text("صور/ملفات $otherCount") },
                )
            }
        }
        item {
            FilterChip(
                selected = showAll,
                onClick = onShowAll,
                label = { Text(if (showAll) "كل الصيغ" else "عرض الكل") },
            )
        }
    }
}

@Composable
private fun EmptyResultsState(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    canRetry: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text("تعذر تجهيز المحتوى", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = canRetry, onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("إعادة المحاولة")
                }
                TextButton(onClick = onOpenDiagnostics) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("التفاصيل")
                }
            }
        }
    }
}

@Composable
private fun RemoteThumbnail(
    url: String?,
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) {
                null
            } else {
                runCatching {
                    val connection = URL(url).openConnection() as? HttpURLConnection
                        ?: return@runCatching null
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = true
                    if (connection.responseCode !in 200..299) {
                        connection.disconnect()
                        return@runCatching null
                    }
                    connection.inputStream.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }.also { connection.disconnect() }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "معاينة الوسائط",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Rounded.VideoLibrary,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun recommendationLabel(model: MediaPresentationModel): String? = when (model.recommendation) {
    MediaResultRecommendation.BestOverall -> "الأفضل"
    MediaResultRecommendation.BestQuality -> "أفضل جودة"
    MediaResultRecommendation.SmallestSize -> "أصغر حجم"
    MediaResultRecommendation.None -> null
}

private fun buildQualityLine(model: MediaPresentationModel): String {
    val format = model.candidate.format
    return when {
        format.kind == MediaKind.Video -> "${model.qualityLabel} · ${containerLabel(format.container)}"
        format.kind == MediaKind.Audio -> "${model.qualityLabel} · ${containerLabel(format.container)}"
        else -> model.qualityLabel
    }
}

private fun buildDetailLine(model: MediaPresentationModel): String {
    val format = model.candidate.format
    val parts = buildList {
        model.codecLabel?.let(::add)
        model.fpsLabel?.let(::add)
        if (format.hasVideo && format.hasAudio) add("فيديو + صوت")
        else if (format.hasVideo) add("فيديو فقط")
        else if (format.hasAudio) add("صوت فقط")
        if (model.sizeLabel == null) add("الحجم يحسب أثناء التنزيل")
    }
    return parts.joinToString(" · ")
}

private fun mediaKindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Video -> "فيديو"
    MediaKind.Audio -> "صوت"
    MediaKind.Image -> "صورة"
    MediaKind.Unknown -> "وسائط"
}

private fun mediaKindShortLabel(format: com.ahdownload.domain.resolver.MediaFormat): String = when {
    format.hasVideo && format.hasAudio -> "فيديو + صوت"
    format.hasVideo -> "فيديو"
    format.hasAudio -> "صوت"
    format.kind == MediaKind.Image -> "صورة"
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
        "اختار المستخدم مصدرًا من النتائج الذكية",
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

private fun containerLabel(container: MediaContainer): String = when (container) {
    MediaContainer.Mp4 -> "MP4"
    MediaContainer.Webm -> "WebM"
    MediaContainer.Mkv -> "MKV"
    MediaContainer.Mov -> "MOV"
    MediaContainer.M4a -> "M4A"
    MediaContainer.Mp3 -> "MP3"
    MediaContainer.Aac -> "AAC"
    MediaContainer.Ogg -> "OGG"
    MediaContainer.Flac -> "FLAC"
    MediaContainer.ThreeGp -> "3GP"
    MediaContainer.Avi -> "AVI"
    MediaContainer.Unknown -> "غير معروفة"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return (totalSeconds / 60L).toString() + ":" + (totalSeconds % 60L).toString().padStart(2, '0')
}
