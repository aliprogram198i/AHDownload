package com.ahdownload.feature.studio

import com.ahdownload.core.designsystem.DiagnosticButton
import com.ahdownload.core.designsystem.DiagnosticOutlinedButton
import com.ahdownload.core.designsystem.DiagnosticIconButton

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaInspection(
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val bitrateKbps: Int?,
    val videoMimeType: String?,
    val audioMimeType: String?,
    val trackCount: Int,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StudioRoute(
    record: DownloadRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onExtractAudio: suspend (DownloadRecord) -> Boolean,
    onBack: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var inspection by remember(record.task.id, record.updatedAtEpochMs) { mutableStateOf<MediaInspection?>(null) }
    var error by remember(record.task.id, record.updatedAtEpochMs) { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var extractMessage by remember { mutableStateOf<String?>(null) }
    var extractSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uiContext = com.ahdownload.core.designsystem.rememberUiTraceContext()

    LaunchedEffect(record.task.id, record.updatedAtEpochMs) {
        inspection = null
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { inspectMedia(context, record) }
        }
        result.onSuccess { inspection = it }.onFailure {
            error = "تعذر قراءة خصائص الملف المحلي."
        }
        uiTraceLogger.snapshot(
            screen = "STUDIO",
            component = "StudioScreen",
            components = "topbar,metadata,open,share,extract_audio",
            stateSummary = "inspection=" + (inspection != null) + ";error=" + (error != null),
            context = uiContext,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Studio") },
                navigationIcon = {
                    DiagnosticIconButton(
            trackingScreen = "STUDIO",
            trackingId = "STUDIO.iconbutton.01",
            trackingLabel = "iconbutton_control",
            disabledReason = "callsite_precondition_not_explicit",onClick = {
                        uiTraceLogger.interaction("STUDIO", "back_button", "back")
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            record.task.displayName ?: "ملف الوسائط",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "فحص محلي للملف المكتمل بدون إعادة تنزيله أو تعديل المصدر.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (inspection == null && error == null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("جارٍ قراءة خصائص الملف...")
                        }
                    }
                }
            }

            error?.let {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            inspection?.let { info ->
                item { InspectionCard(info) }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticButton(
            trackingScreen = "STUDIO",
            trackingId = "STUDIO.button.01",
            trackingLabel = "button_control",
            disabledReason = "callsite_precondition_not_explicit",
                            onClick = {
                                uiTraceLogger.interaction("STUDIO", "open_button", "open")
                                onOpen()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("فتح الملف")
                        }
                        DiagnosticOutlinedButton(
            trackingScreen = "STUDIO",
            trackingId = "STUDIO.outlinedbutton.01",
            trackingLabel = "outlinedbutton_control",
            disabledReason = "callsite_precondition_not_explicit",
                            onClick = {
                                uiTraceLogger.interaction("STUDIO", "share_button", "share")
                                onShare()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("مشاركة")
                        }
                    }
                }

                extractMessage?.let { message ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    if (extractSucceeded) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (extractSucceeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    message,
                                    color = if (extractSucceeded) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                }

                val canExtractAudio = info.audioMimeType?.startsWith("audio/mp4a") == true ||
                    info.audioMimeType == "audio/aac"
                if (record.task.mediaKind == MediaKind.Video && canExtractAudio) {
                    item {
                        DiagnosticButton(
            trackingScreen = "STUDIO",
            trackingId = "STUDIO.button.02",
            trackingLabel = "button_control",
            disabledReason = "callsite_precondition_not_explicit",
                            enabled = !extracting,
                            onClick = {
                                extracting = true
                                scope.launch {
                                    val success = runCatching { onExtractAudio(record) }.getOrDefault(false)
                                    extracting = false
                                    extractSucceeded = success
                                    extractMessage = if (success) {
                                        "تم استخراج الصوت الأصلي وحفظه في مجلد الموسيقى."
                                    } else {
                                        "تعذر استخراج الصوت من هذا الملف. لا يزال الملف الأصلي محفوظًا."
                                    }
                                    uiTraceLogger.interaction("STUDIO", "extract_audio_button", if (success) "success" else "failed")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.AudioFile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (extracting) "جارٍ استخراج الصوت..." else "استخراج الصوت الأصلي")
                        }
                    }
                }
            }

            item {
                Text(
                    "الاستوديو لا يضغط أو يقسم الفيديو تلقائيًا؛ يحافظ على الملف الأصلي.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InspectionCard(info: MediaInspection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("معلومات الوسائط", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            InspectionRow("النوع", info.mimeType ?: "غير معروف")
            info.sizeBytes?.let { InspectionRow("الحجم", formatBytes(it)) }
            info.durationMs?.let { InspectionRow("المدة", formatDuration(it)) }
            if (info.width != null && info.height != null) {
                InspectionRow("الأبعاد", info.width.toString() + " × " + info.height)
            }
            info.bitrateKbps?.takeIf { it > 0 }?.let { InspectionRow("معدل البت", "$it kbps") }
            info.videoMimeType?.let { InspectionRow("مسار الفيديو", it) }
            info.audioMimeType?.let { InspectionRow("مسار الصوت", it) }
            InspectionRow("المسارات", info.trackCount.toString())
        }
    }
}

@Composable
private fun InspectionRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun inspectMedia(context: Context, record: DownloadRecord): MediaInspection {
    val retriever = MediaMetadataRetriever()
    var descriptor: android.os.ParcelFileDescriptor? = null
    try {
        val rawUri = record.destinationUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        var sizeBytes: Long? = null
        if (rawUri != null) {
            descriptor = context.contentResolver.openFileDescriptor(rawUri, "r")
                ?: error("Unable to open content URI")
            sizeBytes = descriptor!!.statSize.takeIf { it >= 0L }
            retriever.setDataSource(descriptor!!.fileDescriptor)
        } else {
            val file = java.io.File(record.task.destinationPath)
            require(file.isFile) { "Local media file is missing" }
            sizeBytes = file.length()
            retriever.setDataSource(file.absolutePath)
        }

        val trackInfo = if (descriptor != null) {
            extractTracks(descriptor.fileDescriptor)
        } else {
            extractTracks(record.task.destinationPath)
        }
        return MediaInspection(
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            sizeBytes = sizeBytes,
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
            bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000),
            videoMimeType = trackInfo.videoMime,
            audioMimeType = trackInfo.audioMime,
            trackCount = trackInfo.count,
        )
    } finally {
        runCatching { retriever.release() }
        runCatching { descriptor?.close() }
    }
}

private data class TrackInfo(val count: Int, val videoMime: String?, val audioMime: String?)

private fun extractTracks(source: Any): TrackInfo {
    val extractor = MediaExtractor()
    return try {
        when (source) {
            is java.io.FileDescriptor -> extractor.setDataSource(source)
            is String -> extractor.setDataSource(source)
            else -> return TrackInfo(0, null, null)
        }
        var video: String? = null
        var audio: String? = null
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && video == null -> video = mime
                mime.startsWith("audio/") && audio == null -> audio = mime
            }
        }
        TrackInfo(extractor.trackCount, video, audio)
    } finally {
        extractor.release()
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index.coerceAtLeast(0)])
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, remainder)
    else "%02d:%02d".format(minutes, remainder)
}
