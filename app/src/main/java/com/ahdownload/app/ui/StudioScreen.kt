package com.ahdownload.app.ui

import android.content.ContentValues
import android.content.Context
import java.io.File
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahdownload.app.diagnostics.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun StudioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var mime by remember { mutableStateOf("") }
    var size by remember { mutableStateOf<Long?>(null) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    var dimensions by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedUri = uri
        message = null
        error = null
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val meta = readMediaMeta(context, uri)
                    Triple(meta, queryName(context, uri), querySize(context, uri))
                }
            }.onSuccess { result ->
                durationMs = result.first.durationMs
                dimensions = result.first.dimensions
                mime = result.first.mime
                name = result.second
                size = result.third
                AppLogger.info(context, "studio.file_selected", "mime=" + result.first.mime + " size=" + (result.third ?: 0L))
            }.onFailure {
                selectedUri = null
                error = "تعذر قراءة الملف. اختر ملف فيديو أو صوت صالحاً."
                AppLogger.error(context, "studio.file_read_failed", it)
            }
            busy = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Smart Studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "أدوات محلية للملفات التي تختارها. لا يتم رفع الملف إلى خادم AHDownload.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Button(
                onClick = { picker.launch(arrayOf("video/*", "audio/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(if (selectedUri == null) "اختيار فيديو أو صوت" else "اختيار ملف آخر")
            }
        }
        error?.let { text ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.ErrorOutline, null) },
                        headlineContent = { Text("تعذر معالجة الملف") },
                        supportingContent = { Text(text) }
                    )
                }
            }
        }
        selectedUri?.let { uri ->
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(name.ifBlank { "ملف محدد" }, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                mime.takeIf { it.isNotBlank() },
                                size?.let(::formatBytes),
                                durationMs?.takeIf { it > 0 }?.let(::formatDuration),
                                dimensions
                            ).joinToString(" • "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        Text("أدوات متاحة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                        if (mime.startsWith("video/")) {
                            FilledTonalButton(
                                onClick = {
                                    busy = true
                                    error = null
                                    message = null
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) { extractAudioToDownloads(context, uri, name) }
                                        }.onSuccess { output ->
                                            message = "تم استخراج الصوت وحفظه في مجلد التنزيلات: $output"
                                            AppLogger.info(context, "studio.audio_extracted", "source=" + AppLogger.fingerprint(uri.toString()))
                                        }.onFailure { failure ->
                                            error = "تعذر استخراج المسار الصوتي من هذا الملف."
                                            AppLogger.error(context, "studio.audio_extract_failed", failure)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Audiotrack, null)
                                Spacer(Modifier.width(8.dp))
                                Text("استخراج الصوت الأصلي")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                message = if (dimensions != null || durationMs != null)
                                    "المعلومات أعلاه تمت قراءتها مباشرة من الملف المحدد."
                                else
                                    "لم يوفر الملف معلومات وسائط إضافية قابلة للقراءة."
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, null)
                            Spacer(Modifier.width(8.dp))
                            Text("تحديث معلومات الملف")
                        }
                    }
                }
            }
        }
        if (busy) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جاري تنفيذ العملية محلياً…")
                }
            }
        }
        message?.let {
            item {
                AssistChip(
                    onClick = {},
                    label = { Text(it) },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Security, null) },
                    headlineContent = { Text("المعالجة المحلية") },
                    supportingContent = {
                        Text("Smart Studio لا يرفع ملفاتك. الخيارات غير المدعومة لا يتم عرضها كأنها جاهزة.")
                    }
                )
            }
        }
    }
}

private data class MediaMeta(val mime: String, val durationMs: Long?, val dimensions: String?)

private fun readMediaMeta(context: Context, uri: Uri): MediaMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        MediaMeta(mime, duration, if ((width ?: 0) > 0 && (height ?: 0) > 0) "${width}×${height}" else null)
    } finally {
        retriever.release()
    }
}

private fun queryName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0).orEmpty()
    }
    return "AHDownload Studio"
}

private fun querySize(context: Context, uri: Uri): Long? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
    }
    return null
}

private fun extractAudioToDownloads(context: Context, uri: Uri, sourceName: String): String {
    val resolver = context.contentResolver
    val safeBase = sourceName.substringBeforeLast('.', sourceName)
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(80)
        .ifBlank { "AHDownload_audio" }
    if (Build.VERSION.SDK_INT < 29) {
        return extractAudioToLegacyExternalFiles(context, uri, safeBase)
    }
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, safeBase + "_audio.m4a")
        put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Unable to create output file")
    var committed = false
    try {
        resolver.openFileDescriptor(uri, "r").use { inputPfd ->
            resolver.openFileDescriptor(outputUri, "rw").use { outputPfd ->
                requireNotNull(inputPfd)
                requireNotNull(outputPfd)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(inputPfd.fileDescriptor)
                    var audioTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val type = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (type.startsWith("audio/")) {
                            audioTrack = i
                            break
                        }
                    }
                    require(audioTrack >= 0) { "No audio track" }
                    val format = extractor.getTrackFormat(audioTrack)
                    extractor.selectTrack(audioTrack)
                    val muxer = MediaMuxer(
                        outputPfd.fileDescriptor,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                    try {
                        val outTrack = muxer.addTrack(format)
                        muxer.start()
                        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
                        val info = android.media.MediaCodec.BufferInfo()
                        while (true) {
                            info.offset = 0
                            info.size = extractor.readSampleData(buffer, 0)
                            if (info.size < 0) break
                            info.presentationTimeUs = extractor.sampleTime
                            info.flags = extractor.sampleFlags
                            muxer.writeSampleData(outTrack, buffer, info)
                            extractor.advance()
                        }
                        muxer.stop()
                    } finally {
                        muxer.release()
                    }
                } finally {
                    extractor.release()
                }
            }
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(outputUri, done, null, null)
        committed = true
        return safeBase + "_audio.m4a"
    } finally {
        if (!committed) resolver.delete(outputUri, null, null)
    }
}

private fun extractAudioToLegacyExternalFiles(context: Context, uri: Uri, safeBase: String): String {
    val outputDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        ?: error("External files directory unavailable")
    outputDir.mkdirs()
    val output = File(outputDir, safeBase + "_audio.m4a")
    if (output.exists()) output.delete()
    context.contentResolver.openFileDescriptor(uri, "r").use { inputPfd ->
        requireNotNull(inputPfd)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPfd.fileDescriptor)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val type = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (type.startsWith("audio/")) { audioTrack = i; break }
            }
            require(audioTrack >= 0) { "No audio track" }
            val format = extractor.getTrackFormat(audioTrack)
            extractor.selectTrack(audioTrack)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
                val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally { muxer.release() }
        } finally { extractor.release() }
    }
    return output.name
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var n = value.toDouble()
    var index = -1
    while (n >= 1024 && index < units.lastIndex) {
        n /= 1024
        index++
    }
    return String.format(Locale.US, "%.1f %s", n, units[index])
}
