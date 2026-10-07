package com.ahdownload.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.resolver.MediaResultGroup
import com.ahdownload.domain.resolver.MediaResultRecommendation
import com.ahdownload.domain.resolver.SmartResultEngine

@Composable
fun HomeRoute(
    initialUrl: String? = null,
    onDownloadRequested: suspend (MediaCandidate, String?, String?) -> Boolean,
    logger: DiagnosticLogger,
    onOpenDiagnostics: () -> Unit,
    onOpenYouTubeSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
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
        onOpenYouTubeSession = onOpenYouTubeSession,
        onOpenSettings = onOpenSettings,
        onOpenDownloads = onOpenDownloads,
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
    onDownload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenYouTubeSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val androidContext = LocalContext.current
    val candidates = state.resolution?.candidates.orEmpty()
    val engine = remember { SmartResultEngine() }
    val resultSet = remember(candidates) { engine.build(candidates) }
    var showAll by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(MediaResultGroup.Video) }

    LaunchedEffect(
        state.analyzing,
        state.resolving,
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
                title = { Text("AHDownload") },
                navigationIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Rounded.Download, contentDescription = "التنزيلات")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "الإعدادات")
                    }
                    IconButton(onClick = onOpenYouTubeSession) {
                        Icon(Icons.Rounded.AccountCircle, contentDescription = "جلسة YouTube")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = "سجل الأخطاء")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("مركز التحميل الذكي", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "ألصق الرابط، وسنرتب أفضل المصادر الحقيقية تلقائيًا ثم نعرضها بشكل واضح.",
                        style = MaterialTheme.typography.bodyLarge,
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
                            "الرابط والمصدر",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = state.url,
                            onValueChange = {
                                showAll = false
                                onUrlChanged(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            label = { Text("رابط المحتوى") },
                            placeholder = { Text("الصق رابط YouTube أو أي مصدر مباشر") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                enabled = !state.analyzing && !state.resolving,
                                onClick = {
                                    val clipboard = androidContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val text = clipboard?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(androidContext)
                                        ?.toString()
                                        ?.trim()
                                        .orEmpty()
                                    if (text.isNotBlank()) {
                                        showAll = false
                                        onUrlChanged(text)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("لصق الرابط")
                            }
                            OutlinedButton(
                                enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                                onClick = {
                                    showAll = false
                                    onUrlChanged("")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("مسح")
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
                        else -> "تحليل واستخراج الوسائط"
                    },
                    enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.analyzing || state.resolving) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            state.result?.let { link ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AHStatusPill(link.platform.name)
                        if (link.kind != MediaKind.Unknown) AHStatusPill(link.kind.name)
                    }
                }
            }

            state.resolution?.let { resolution ->
                item {
                    ResultSummaryCard(
                        title = resolution.title ?: "نتائج الرابط",
                        platform = state.result?.platform?.name,
                        durationMs = resolution.durationMs,
                        total = resultSet.all.size,
                        video = resultSet.video.size,
                        audio = resultSet.audio.size,
                        hidden = resultSet.hiddenCount,
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
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }

                val alternatives = listOfNotNull(
                    resultSet.bestQuality?.takeUnless { it.candidate.id == resultSet.bestOverall?.candidate?.id },
                    resultSet.smallestSize?.takeUnless {
                        it.candidate.id == resultSet.bestOverall?.candidate?.id ||
                            it.candidate.id == resultSet.bestQuality?.candidate?.id
                    },
                )

                if (alternatives.isNotEmpty()) {
                    item { Text("اختيارات ذكية", style = MaterialTheme.typography.titleLarge) }
                    items(alternatives, key = { "smart-" + it.candidate.id }) { model ->
                        ResultOptionCard(
                            model = model,
                            selected = model.candidate.id == state.selectedCandidateId,
                            validating = model.candidate.id == state.validatingCandidateId,
                            onSelect = {
                                logSelection(logger, model, state.selectedCandidateId)
                                onSelectCandidate(model.candidate.id)
                            },
                            onDownload = onDownload,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { filter = MediaResultGroup.Video }) { Text("فيديو") }
                        Button(onClick = { filter = MediaResultGroup.Audio }) { Text("صوت") }
                        Button(onClick = { showAll = !showAll }) {
                            Text(if (showAll) "إخفاء الخيارات" else "كل الخيارات")
                        }
                    }
                }

                val filtered = if (showAll) {
                    when (filter) {
                        MediaResultGroup.Video -> resultSet.video
                        MediaResultGroup.Audio -> resultSet.audio
                        MediaResultGroup.Other -> resultSet.other
                    }
                } else {
                    resultSet.visible.filter { it.group == filter }
                }

                if (filtered.isNotEmpty()) {
                    item {
                        Text(
                            if (showAll) "كل الخيارات المتاحة" else "الخيارات المقترحة",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
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
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                } else {
                    item {
                        Text(
                            "لا توجد خيارات في هذا التصنيف.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (state.downloadQueued) {
                item { AHStatusPill("تمت إضافة التنزيل إلى قائمة الانتظار.") }
            }

            item {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    "المصدر يعاد التحقق منه قبل إدخاله إلى Queue؛ لا يتم عرض مصدر غير صالح كتنزيل مؤكد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(
    title: String,
    platform: String?,
    durationMs: Long?,
    total: Int,
    video: Int,
    audio: Int,
    hidden: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("نتائج الرابط", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                platform?.let { AHStatusPill(it) }
                durationMs?.let { AHStatusPill(formatDuration(it)) }
                AHStatusPill("$total مصدر")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (video > 0) AHStatusPill("$video فيديو")
                if (audio > 0) AHStatusPill("$audio صوت")
                if (hidden > 0) AHStatusPill("+$hidden مخفي")
            }
            Text(
                "تم تنظيف النتائج ودمج المتكرر وترتيبها حسب الجودة والتوافق والحجم قبل العرض.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onOpenDiagnostics: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !validating, onClick = onSelect)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AHStatusPill("التوصية الأولى")
                recommendationLabel(model)?.let { AHStatusPill(it) }
                if (selected) AHStatusPill("محدد")
            }
            Text("أفضل اختيار للتنزيل", style = MaterialTheme.typography.titleLarge)
            Text(buildQualityLine(model), style = MaterialTheme.typography.titleMedium)
            Text(buildDetailLine(model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                model.sizeLabel?.let { AHStatusPill(it) }
                model.fpsLabel?.let { AHStatusPill(it) }
                if (model.candidate.format.hasVideo && model.candidate.format.hasAudio) AHStatusPill("فيديو + صوت")
            }
            Button(enabled = selected && !validating, onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (validating) "جارٍ التحقق من المصدر..." else "تنزيل هذا الاختيار")
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
    onOpenDiagnostics: () -> Unit,
) {
    val format = model.candidate.format
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !validating, onClick = onSelect)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AHStatusPill(model.qualityLabel)
                AHStatusPill(containerLabel(format.container))
                if (selected) AHStatusPill("محدد")
                recommendationLabel(model)?.let { AHStatusPill(it) }
            }
            Text(buildQualityLine(model), style = MaterialTheme.typography.titleMedium)
            Text(buildDetailLine(model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                model.sizeLabel?.let { AHStatusPill(it) }
                if (format.hasVideo && format.hasAudio) AHStatusPill("فيديو + صوت")
                else if (format.hasVideo) AHStatusPill("فيديو")
                else if (format.hasAudio) AHStatusPill("صوت")
            }
            OutlinedButton(enabled = selected && !validating, onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (validating) "جارٍ التحقق..." else "تنزيل")
            }
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

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return (totalSeconds / 60L).toString() + ":" + (totalSeconds % 60L).toString().padStart(2, '0')
}
