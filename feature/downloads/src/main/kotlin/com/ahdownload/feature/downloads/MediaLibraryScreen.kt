package com.ahdownload.feature.downloads

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * A separate library view over completed download records. It deliberately reuses the existing
 * repository and playback callback rather than introducing a second database or media player.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryRoute(
    repository: DownloadRepository,
    onBack: () -> Unit,
    onOpenMedia: (DownloadRecord) -> Unit,
) {
    val context = LocalContext.current
    val history by repository.observeHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(MediaLibraryFilter.All) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<MediaLibraryEntry>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(history, refreshGeneration) {
        isRefreshing = true
        entries = withContext(Dispatchers.IO) {
            history.asSequence()
                .filter { it.status == DownloadStatus.COMPLETED }
                .mapNotNull { record ->
                    val type = classifyMediaLibraryRecord(record) ?: return@mapNotNull null
                    if (!localMediaRecordExists(context, record)) return@mapNotNull null
                    MediaLibraryEntry(record, type)
                }
                .toList()
        }
        isRefreshing = false
    }

    val visibleEntries = remember(entries, selectedFilter, searchQuery) {
        filterMediaLibraryEntries(entries, selectedFilter, searchQuery)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("مكتبة الوسائط")
                        Text(
                            "ملفات الفيديو والصوت المكتملة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshGeneration++ }, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "تحديث المكتبة")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("البحث في الملفات") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaLibraryFilter.entries.forEach { filter ->
                    val count = when (filter) {
                        MediaLibraryFilter.All -> entries.size
                        MediaLibraryFilter.Video -> entries.count { it.type == MediaLibraryType.Video }
                        MediaLibraryFilter.Audio -> entries.count { it.type == MediaLibraryType.Audio }
                    }
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text("${filter.label} (${count})") },
                    )
                }
            }

            when {
                visibleEntries.isEmpty() && isRefreshing -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                visibleEntries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (selectedFilter == MediaLibraryFilter.Audio) {
                                    Icons.Rounded.AudioFile
                                } else {
                                    Icons.Rounded.VideoFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                if (entries.isEmpty()) {
                                    "لا توجد ملفات وسائط مكتملة ومتاحة بعد"
                                } else {
                                    "لا توجد ملفات تطابق البحث أو الفلتر"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "تظهر هنا الملفات المكتملة من سجل التنزيلات",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "media-library-summary") {
                            Text(
                                "${visibleEntries.size} ملف",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(visibleEntries, key = { it.record.task.id }) { entry ->
                            MediaLibraryItemCard(entry = entry, onClick = { onOpenMedia(entry.record) })
                        }
                        item(key = "media-library-bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaLibraryItemCard(
    entry: MediaLibraryEntry,
    onClick: () -> Unit,
) {
    val record = entry.record
    val title = record.task.displayName?.takeIf { it.isNotBlank() } ?: "ملف وسائط"
    val icon = if (entry.type == MediaLibraryType.Audio) {
        Icons.Rounded.AudioFile
    } else {
        Icons.Rounded.VideoFile
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.type.label} · ${formatMediaBytes(record.bytesDownloaded)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "اكتمل التنزيل",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "تشغيل ${title}",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun localMediaRecordExists(context: Context, record: DownloadRecord): Boolean {
    val rawDestination = record.destinationUri?.takeIf { it.isNotBlank() }
    if (rawDestination != null) {
        val uri = runCatching { Uri.parse(rawDestination) }.getOrNull()
        when (uri?.scheme?.lowercase(Locale.ROOT)) {
            "content" -> {
                val readable = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (readable) return true
            }
            "file" -> {
                if (uri.path?.let(::File)?.isFile == true) return true
            }
        }
    }
    return runCatching { File(record.task.destinationPath).isFile }.getOrDefault(false)
}

private fun formatMediaBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0L)
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var amount = bytes.toDouble()
    var unitIndex = -1
    do {
        amount /= 1_024.0
        unitIndex++
    } while (amount >= 1_024.0 && unitIndex < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", amount, units[unitIndex])
}
