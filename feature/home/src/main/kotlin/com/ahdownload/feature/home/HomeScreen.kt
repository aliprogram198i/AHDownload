package com.ahdownload.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

@Composable
fun HomeRoute(
    initialUrl: String? = null,
    onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean,
    logger: DiagnosticLogger,
    onOpenDiagnostics: () -> Unit,
    onOpenYouTubeSession: () -> Unit,
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
) {
    val candidates = state.resolution?.candidates.orEmpty()
    val recommendedCandidate = candidates
        .sortedWith(
            compareByDescending<MediaCandidate> { it.format.kind == MediaKind.Video && it.format.hasAudio }
                .thenByDescending { it.format.kind == MediaKind.Video && (it.format.height ?: 0) in 1..1080 }
                .thenByDescending { it.format.kind == MediaKind.Video && (it.format.height ?: 0) }
                .thenByDescending { it.format.hasAudio }
                .thenByDescending { it.format.bitrateKbps ?: 0 },
        )
        .firstOrNull()
    val videoCandidates = candidates
        .filter { it.format.kind == MediaKind.Video && it.id != recommendedCandidate?.id }
        .sortedWith(
            compareByDescending<MediaCandidate> { it.format.hasAudio }
                .thenByDescending { it.format.height ?: 0 }
                .thenByDescending { it.format.bitrateKbps ?: 0 },
        )
        .take(6)
    val audioCandidates = candidates
        .filter { it.format.kind == MediaKind.Audio }
        .sortedByDescending { it.format.bitrateKbps ?: 0 }
        .take(4)
    val displayedCandidates = buildList {
        recommendedCandidate?.let { add(it) }
        addAll(videoCandidates)
        addAll(audioCandidates)
    }.distinctBy { it.id }
    val displayedPositionById = displayedCandidates.mapIndexed { index, candidate -> candidate.id to index }.toMap()
    val candidateSectionById = buildMap {
        recommendedCandidate?.let { put(it.id, "recommended") }
        videoCandidates.forEach { put(it.id, "video") }
        audioCandidates.forEach { put(it.id, "audio") }
    }
    val renderSignature = listOf(
        state.analyzing, state.resolving, state.error, state.downloadQueued,
        state.resolution?.title, state.resolution?.durationMs, candidates.size,
        displayedCandidates.joinToString(",") { it.id },
        recommendedCandidate?.id, state.selectedCandidateId, state.validatingCandidateId
    ).joinToString("|")

    LaunchedEffect(renderSignature) {
        val layoutMode = when {
            state.analyzing -> "ANALYZING"
            state.resolving -> "RESOLVING"
            state.resolution == null -> "INPUT"
            candidates.isEmpty() -> "RESULT_EMPTY"
            else -> "RESULT_READY"
        }
        val hiddenCount = candidates.count { it.id !in displayedPositionById }
        logger.log(
            DiagnosticLevel.INFO,
            "SMART_CENTER_RESULT_PRESENTED",
            "تم عرض نتائج الرابط في مركز التحميل الذكي",
            "ui.smart_center.result",
            mapOf(
                "layout_mode" to layoutMode,
                "platform" to (state.result?.platform?.name ?: "unknown"),
                "media_kind" to (state.result?.kind?.name ?: "unknown"),
                "title_present" to (!state.resolution?.title.isNullOrBlank()).toString(),
                "duration_ms" to (state.resolution?.durationMs?.toString() ?: "unknown"),
                "candidate_total" to candidates.size.toString(),
                "displayed_candidate_total" to displayedCandidates.size.toString(),
                "hidden_candidate_total" to hiddenCount.toString(),
                "recommended_candidate_id" to (recommendedCandidate?.id ?: "none"),
                "selected_candidate_id" to (state.selectedCandidateId ?: "none"),
                "validation_candidate_id" to (state.validatingCandidateId ?: "none"),
                "has_error" to (state.error != null).toString(),
                "download_queued" to state.downloadQueued.toString(),
                "presentation_policy" to "recommended_first;video_max_6;audio_max_4;remaining_hidden",
            ),
            null,
        )
        logger.log(
            DiagnosticLevel.INFO,
            "SMART_CENTER_ORDERING",
            "تم تحديد ترتيب النتائج وطريقة عرضها",
            "ui.smart_center.ordering",
            mapOf(
                "ordering_algorithm" to "recommended_first;video_quality_ranked;audio_bitrate_ranked",
                "source_candidate_total" to candidates.size.toString(),
                "visible_candidate_total" to displayedCandidates.size.toString(),
                "hidden_candidate_total" to hiddenCount.toString(),
                "recommended_candidate_id" to (recommendedCandidate?.id ?: "none"),
                "recommended_selection_reason" to when {
                    recommendedCandidate?.format?.kind == MediaKind.Video && recommendedCandidate.format.hasAudio ->
                        "video_with_audio"
                    recommendedCandidate?.format?.kind == MediaKind.Video -> "first_video_fallback"
                    recommendedCandidate?.format?.kind == MediaKind.Audio -> "first_audio_fallback"
                    else -> "none"
                },
            ),
            null,
        )
        candidates.forEachIndexed { sourceIndex, candidate ->
            val displayPosition = displayedPositionById[candidate.id]
            logger.log(
                if (displayPosition != null) DiagnosticLevel.INFO else DiagnosticLevel.INFO,
                if (displayPosition != null) "SMART_CENTER_OPTION_VISIBLE" else "SMART_CENTER_OPTION_HIDDEN",
                if (displayPosition != null) "خيار وسائط ظهر فعليًا في الشاشة" else "خيار وسائط استُخرج ولم يظهر في الشاشة",
                "ui.smart_center.result",
                mapOf(
                    "source_rank" to sourceIndex.toString(),
                    "display_position" to (displayPosition?.toString() ?: "hidden"),
                    "section" to (candidateSectionById[candidate.id] ?: "hidden"),
                    "visibility" to if (displayPosition != null) "VISIBLE" else "HIDDEN",
                    "visibility_reason" to when {
                        displayPosition != null && candidate.id == recommendedCandidate?.id -> "recommended_first"
                        displayPosition != null -> "section_limit"
                        candidate.format.kind == MediaKind.Video -> "video_section_limit"
                        candidate.format.kind == MediaKind.Audio -> "audio_section_limit"
                        else -> "unsupported_or_unclassified_kind"
                    },
                    "candidate_id" to candidate.id,
                    "kind" to candidate.format.kind.name,
                    "container" to candidate.format.container.name,
                    "width" to (candidate.format.width?.toString() ?: "unknown"),
                    "height" to (candidate.format.height?.toString() ?: "unknown"),
                    "fps" to (candidate.format.fps?.toString() ?: "unknown"),
                    "bitrate_kbps" to (candidate.format.bitrateKbps?.toString() ?: "unknown"),
                    "size_bytes" to (candidate.format.fileSizeBytes?.toString() ?: "unknown"),
                    "has_video" to candidate.format.hasVideo.toString(),
                    "has_audio" to candidate.format.hasAudio.toString(),
                    "selected" to (candidate.id == state.selectedCandidateId).toString(),
                    "recommended" to (candidate.id == recommendedCandidate?.id).toString(),
                    "validating" to (candidate.id == state.validatingCandidateId).toString(),
                ),
                null,
            )
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            logger.log(
                DiagnosticLevel.ERROR,
                "SMART_CENTER_ERROR_VISIBLE",
                "ظهر خطأ للمستخدم داخل مركز التحميل الذكي",
                "ui.smart_center.error",
                mapOf(
                    "layout_mode" to when {
                        state.analyzing -> "ANALYZING"
                        state.resolving -> "RESOLVING"
                        state.resolution == null -> "INPUT_OR_EMPTY"
                        else -> "RESULT"
                    },
                    "platform" to (state.result?.platform?.name ?: "unknown"),
                    "candidate_total" to candidates.size.toString(),
                    "selected_candidate_id" to (state.selectedCandidateId ?: "none"),
                    "validating_candidate_id" to (state.validatingCandidateId ?: "none"),
                    "error_visible" to "true",
                    "error_message" to error,
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
                    IconButton(onClick = onOpenYouTubeSession) { Icon(Icons.Rounded.AccountCircle, contentDescription = "جلسة YouTube") }
                    IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Rounded.ErrorOutline, contentDescription = "سجل الأخطاء") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("مركز التحميل الذكي", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "ألصق الرابط، استخرج الوسائط الحقيقية، اختر الجودة ثم أضفها إلى قائمة التنزيل.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    label = { Text("رابط المحتوى") },
                    placeholder = { Text("https://...") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
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
                        AHStatusPill(link.kind.name)
                    }
                }
            }
            state.resolution?.let { resolution ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(resolution.title ?: "وسائط متاحة", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.result?.platform?.name?.let { AHStatusPill(it) }
                            state.result?.kind?.name?.let { AHStatusPill(it) }
                            resolution.durationMs?.let { AHStatusPill(formatDuration(it)) }
                        }
                    }
                }
                recommendedCandidate?.let { candidate ->
                    item {
                        CandidateCard(
                            candidate = candidate,
                            selected = candidate.id == state.selectedCandidateId,
                            validating = candidate.id == state.validatingCandidateId,
                            recommended = true,
                            onSelect = {
                                logger.log(
                                    DiagnosticLevel.INFO,
                                    "SMART_CENTER_OPTION_SELECTED",
                                    "اختار المستخدم خيار وسائط من النتائج المعروضة",
                                    "ui.smart_center.selection",
                                    mapOf(
                                        "candidate_id" to candidate.id,
                                        "display_position" to (displayedPositionById[candidate.id]?.toString() ?: "hidden"),
                                        "section" to (candidateSectionById[candidate.id] ?: "hidden"),
                                        "selected_before" to (candidate.id == state.selectedCandidateId).toString(),
                                        "kind" to candidate.format.kind.name,
                                        "height" to (candidate.format.height?.toString() ?: "unknown"),
                                        "bitrate_kbps" to (candidate.format.bitrateKbps?.toString() ?: "unknown"),
                                    ),
                                    null,
                                )
                                onSelectCandidate(candidate.id)
                            },
                            onDownload = onDownload,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }
                if (videoCandidates.isNotEmpty()) {
                    item { Text("خيارات الفيديو", style = MaterialTheme.typography.titleLarge) }
                    items(videoCandidates, key = { "video-" + it.id }) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            selected = candidate.id == state.selectedCandidateId,
                            validating = candidate.id == state.validatingCandidateId,
                            recommended = false,
                            onSelect = { onSelectCandidate(candidate.id) },
                            onDownload = onDownload,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }
                if (audioCandidates.isNotEmpty()) {
                    item { Text("خيارات الصوت", style = MaterialTheme.typography.titleLarge) }
                    items(audioCandidates, key = { "audio-" + it.id }) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            selected = candidate.id == state.selectedCandidateId,
                            validating = candidate.id == state.validatingCandidateId,
                            recommended = false,
                            onSelect = { onSelectCandidate(candidate.id) },
                            onDownload = onDownload,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }
            }
            state.error?.let { error ->
                item { AHStatusPill(error) }
            }
            if (state.downloadQueued) {
                item { AHStatusPill("تمت إضافة التنزيل إلى قائمة الانتظار.") }
            }
            item {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "المصدر يُتحقق منه قبل الإدخال إلى Queue، والتقدم الفعلي يديره WorkManager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: MediaCandidate,
    selected: Boolean,
    validating: Boolean,
    recommended: Boolean = false,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val format = candidate.format
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !validating, onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AHStatusPill(if (format.kind == MediaKind.Audio) "صوت" else "فيديو")
                format.height?.let { AHStatusPill(it.toString() + "p") }
                format.bitrateKbps?.let { AHStatusPill(it.toString() + " kbps") }
                if (recommended) AHStatusPill("⭐ موصى به")
                if (selected) AHStatusPill("محدد")
            }
            Text(
                when {
                    format.kind == MediaKind.Video && format.height != null -> "فيديو " + format.height + "p"
                    format.kind == MediaKind.Audio && format.bitrateKbps != null -> "صوت " + format.bitrateKbps + " kbps"
                    else -> "وسائط"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                buildString {
                    append(containerLabel(format.container))
                    format.videoCodec?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                    format.audioCodec?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                format.fileSizeBytes?.let { AHStatusPill(formatBytes(it)) }
                when {
                    format.hasVideo && format.hasAudio -> AHStatusPill("فيديو + صوت")
                    format.hasVideo -> AHStatusPill("فيديو فقط")
                    format.hasAudio -> AHStatusPill("صوت فقط")
                }
            }
            Button(
                enabled = selected && !validating,
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (validating) "جارٍ التحقق..." else "تنزيل")
            }
        }
    }
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

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    bytes < 1024L * 1024L * 1024L -> (bytes / (1024L * 1024L)).toString() + " MB"
    else -> (bytes / (1024L * 1024L * 1024L)).toString() + " GB"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return (totalSeconds / 60L).toString() + ":" + (totalSeconds % 60L).toString().padStart(2, '0')
}
