package com.ahdownload.feature.home

import com.ahdownload.core.designsystem.tracedClickable
import com.ahdownload.core.designsystem.DiagnosticOutlinedButton
import com.ahdownload.core.designsystem.DiagnosticTextButton
import com.ahdownload.core.designsystem.DiagnosticIconButton
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.designsystem.AHCard
import com.ahdownload.core.designsystem.AHSectionHeader
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.resolver.MediaResultRecommendation
import java.util.UUID
import com.ahdownload.core.designsystem.DiagnosticFilterChip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import com.ahdownload.domain.favorites.FavoriteItem
import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchKind

@Composable
internal fun MediaThumbnail(
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
internal fun UnifiedDownloadResultCard(
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
                    trackingId = "HOME.favorite.toggle",
                    trackingLabel = if (favorite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
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

@Composable
internal fun SearchResultCard(
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
internal fun FavoriteLinksCard(
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
