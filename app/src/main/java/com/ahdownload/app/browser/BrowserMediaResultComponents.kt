package com.ahdownload.app.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahdownload.core.designsystem.AHCard
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.resolver.MediaCandidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserMediaResultsSheet(
    resultTitle: String?,
    resultSourceUrl: String?,
    resultThumbnail: String?,
    videoCandidates: List<MediaCandidate>,
    audioSources: List<MediaCandidate>,
    selectedAudioCandidateId: String?,
    selectedAudioFormat: AudioOutputFormat?,
    actionInProgress: Boolean,
    onDismiss: () -> Unit,
    onSelectAudioCandidate: (String) -> Unit,
    onSelectAudioFormat: (AudioOutputFormat) -> Unit,
    onDownloadVideo: (MediaCandidate) -> Unit,
    onExtractAudio: (MediaCandidate, AudioOutputFormat) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("خيارات التنزيل", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    resultTitle ?: "مصادر الصفحة",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    "${videoCandidates.size} خيار فيديو • ${audioSources.size} مصدر صوت • لا يتم اختيار أي مصدر تلقائيًا",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text("الفيديو", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (videoCandidates.isEmpty()) {
                item { Text("لم يتم العثور على خيار فيديو.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(videoCandidates, key = { "video-${it.id}" }) { candidate ->
                    CandidateOption(
                        candidate = candidate,
                        title = resultTitle,
                        sourceUrl = resultSourceUrl,
                        thumbnailUrl = resultThumbnail,
                        busy = actionInProgress,
                        onDownload = { onDownloadVideo(candidate) },
                    )
                }
            }
            item { Text("استخراج الصوت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (audioSources.isEmpty()) {
                item { Text("لم يتم العثور على مصدر صوت قابل للتحقق.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    Text("اختر المصدر أولًا، ثم صيغة الصوت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        audioSources.forEach { candidate ->
                            FilterChip(
                                selected = selectedAudioCandidateId == candidate.id,
                                onClick = { onSelectAudioCandidate(candidate.id) },
                                label = { Text(BrowserMediaOptionDeduplicator.audioSourceLabel(candidate)) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AudioOutputFormat.entries.forEach { format ->
                            FilterChip(
                                selected = selectedAudioFormat == format,
                                onClick = { onSelectAudioFormat(format) },
                                label = { Text(format.label) },
                            )
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                        enabled = selectedAudioCandidateId != null && selectedAudioFormat != null && !actionInProgress,
                        onClick = {
                            val candidate = audioSources.firstOrNull { it.id == selectedAudioCandidateId } ?: return@Button
                            val format = selectedAudioFormat ?: return@Button
                            onExtractAudio(candidate, format)
                        },
                    ) {
                        Text(if (actionInProgress) "جارٍ التجهيز…" else "استخراج الصوت المحدد")
                    }
                }
            }
        }
    }
}

@Composable
@Composable
internal fun CandidateOption(
    candidate: MediaCandidate,
    title: String?,
    sourceUrl: String?,
    thumbnailUrl: String?,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    // Capture the cross-module property in a local stable value for Kotlin smart-cast safety.
    val fileSizeBytes = candidate.format.fileSizeBytes
    AHCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    BrowserMediaOptionDeduplicator.videoOptionTitle(candidate),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildList {
                        candidate.format.videoCodec?.takeIf { it.isNotBlank() }?.let { add("Video ${it.uppercase()}") }
                        candidate.format.audioCodec?.takeIf { it.isNotBlank() }?.let { add("Audio ${it.uppercase()}") }
                        candidate.format.fps?.let { add("${it} fps") }
                        candidate.format.bitrateKbps?.takeIf { it > 0 }?.let { add("${it} kbps") }
                        add(
                            when {
                                candidate.format.hasAudio -> "صوت مضمّن"
                                !candidate.companionAudioSourceUrl.isNullOrBlank() -> "صوت منفصل"
                                else -> "مسار الصوت غير مؤكد"
                            },
                        )
                        when {
                            candidate.streamingManifest -> add("حجم الفيديو النهائي يحدده البث أثناء التنزيل")
                            fileSizeBytes != null && fileSizeBytes > 0L -> add(formatBytes(fileSizeBytes))
                            else -> add("الحجم غير معروف؛ سيُحدَّد عند التحقق")
                        }
                    }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                enabled = !busy,
                onClick = onDownload,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("تنزيل") }
        }
    }
}
