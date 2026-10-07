package com.ahdownload.feature.downloads

import android.text.format.DateUtils
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
import androidx.compose.material.icons.AutoMirrored
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHBottomNavDestination
import com.ahdownload.core.designsystem.AHBottomNavigationBar
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.model.MediaKind
import java.io.File
import java.util.Locale

private enum class DownloadFilter(val label: String) {
    All("الكل"),
    Active("نشطة"),
    Completed("مكتملة"),
    Failed("فشل"),
}

@Composable
fun DownloadsRoute(
    repository: DownloadRepository,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (DownloadRecord) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
    onShareDownload: (DownloadRecord) -> Unit,
    onDeleteDownloadFile: (DownloadRecord) -> Boolean,
    uiTraceLogger: UiTraceLogger,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateSettings: () -> Unit,
) {
    val controls = remember(repository, onPauseDownload, onResumeDownload, onCancelDownload) {
        object : DownloadControls {
            override fun pause(taskId: String) = onPauseDownload(taskId)
            override fun resume(record: DownloadRecord) = onResumeDownload(record)
            override fun cancel(taskId: String) = onCancelDownload(taskId)
        }
    }
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory(repository, controls))
    val records by vm.records.collectAsStateWithLifecycle()

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(records) {
        val componentNames = buildList {
            add("topbar")
            add("search")
            add("filters")
            if (records.isEmpty()) add("empty_state") else add("download_cards")
            if (records.any { it.status in ACTIVE_STATUSES }) add("active_controls")
            if (records.any { it.status == DownloadStatus.FAILED }) add("retry_controls")
            if (records.any { it.status == DownloadStatus.COMPLETED }) add("completed_actions")
            add("bottom_navigation")
        }.joinToString(",")
        uiTraceLogger.snapshot(
            screen = "DOWNLOADS",
            component = "DownloadsScreen",
            components = componentNames,
            stateSummary = "records=" + records.size +
                ";active=" + records.count { it.status in ACTIVE_STATUSES } +
                ";completed=" + records.count { it.status == DownloadStatus.COMPLETED } +
                ";failed=" + records.count { it.status == DownloadStatus.FAILED },
            context = uiContext,
        )
    }

    DownloadsScreen(
        records = records,
        onBack = onBack,
        onNavigateHome = onNavigateHome,
        onNavigateSettings = onNavigateSettings,
        onPause = vm::pause,
        onResume = vm::resume,
        onCancel = vm::cancel,
        onRetry = vm::retry,
        onDeleteHistory = vm::deleteHistory,
        onOpenDownload = onOpenDownload,
        onShareDownload = onShareDownload,
        onDeleteDownloadFile = onDeleteDownloadFile,
        uiTraceLogger = uiTraceLogger,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(
    records: List<DownloadRecord>,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateSettings: () -> Unit,
    onPause: (DownloadRecord) -> Unit,
    onResume: (DownloadRecord) -> Unit,
    onCancel: (DownloadRecord) -> Unit,
    onRetry: (DownloadRecord) -> Unit,
    onDeleteHistory: (DownloadRecord) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
    onShareDownload: (DownloadRecord) -> Unit,
    onDeleteDownloadFile: (DownloadRecord) -> Boolean,
    uiTraceLogger: UiTraceLogger,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedback) {
        val message = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        feedback = null
    }

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filtered = remember(records, filter, normalizedQuery) {
        records
            .filter { record ->
                val matchesFilter = when (filter) {
                    DownloadFilter.All -> true
                    DownloadFilter.Active -> record.status in ACTIVE_STATUSES
                    DownloadFilter.Completed -> record.status == DownloadStatus.COMPLETED
                    DownloadFilter.Failed -> record.status == DownloadStatus.FAILED
                }
                val title = record.task.displayName.orEmpty()
                val path = record.task.destinationPath
                matchesFilter && (
                    normalizedQuery.isBlank() ||
                        title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                        path.lowercase(Locale.ROOT).contains(normalizedQuery)
                    )
            }
            .sortedWith(
                compareByDescending<DownloadRecord> { it.status in ACTIVE_STATUSES }
                    .thenByDescending { it.updatedAtEpochMs },
            )
    }

    val activeCount = records.count { it.status in ACTIVE_STATUSES }
    val completedCount = records.count { it.status == DownloadStatus.COMPLETED }
    val failedCount = records.count { it.status == DownloadStatus.FAILED }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("التنزيلات")
                        Text(
                            "$activeCount نشطة · $completedCount مكتملة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            uiTraceLogger.interaction("DOWNLOADS", "back_button", "back")
                            onBack()
                        },
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "رجوع")
                    }
                },
            )
        },
        bottomBar = {
            AHBottomNavigationBar(
                selected = AHBottomNavDestination.DOWNLOADS,
                onDestinationSelected = { destination ->
                    when (destination) {
                        AHBottomNavDestination.HOME -> {
                            uiTraceLogger.interaction("DOWNLOADS", "bottom_nav_home", "open_home")
                            onNavigateHome()
                        }
                        AHBottomNavDestination.DOWNLOADS -> Unit
                        AHBottomNavDestination.SETTINGS -> {
                            uiTraceLogger.interaction("DOWNLOADS", "bottom_nav_settings", "open_settings")
                            onNavigateSettings()
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            EmptyDownloads(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onGoHome = onNavigateHome,
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "مسح البحث")
                                }
                            }
                        },
                        placeholder = { Text("ابحث في سجل التنزيلات") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }

                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DownloadFilter.entries.forEach { item ->
                            val count = when (item) {
                                DownloadFilter.All -> records.size
                                DownloadFilter.Active -> activeCount
                                DownloadFilter.Completed -> completedCount
                                DownloadFilter.Failed -> failedCount
                            }
                            item {
                                FilterChip(
                                    selected = item == filter,
                                    onClick = { filter = item },
                                    label = { Text(item.label + " " + count) },
                                )
                            }
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(38.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text("لا توجد نتائج مطابقة", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "غيّر البحث أو الفلتر لعرض عناصر أخرى.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.task.id }) { record ->
                        DownloadRecordCard(
                            record = record,
                            onPause = {
                                uiTraceLogger.interaction("DOWNLOADS", "pause_control", "pause")
                                onPause(record)
                            },
                            onResume = {
                                uiTraceLogger.interaction("DOWNLOADS", "resume_control", "resume")
                                onResume(record)
                            },
                            onCancel = {
                                uiTraceLogger.interaction("DOWNLOADS", "cancel_control", "cancel")
                                onCancel(record)
                            },
                            onRetry = {
                                uiTraceLogger.interaction("DOWNLOADS", "retry_control", "retry")
                                onRetry(record)
                            },
                            onDeleteHistory = {
                                pendingDelete = record
                            },
                            onOpenDownload = {
                                uiTraceLogger.interaction("DOWNLOADS", "open_control", "open")
                                onOpenDownload(record)
                            },
                            onShareDownload = {
                                uiTraceLogger.interaction("DOWNLOADS", "share_control", "share")
                                onShareDownload(record)
                            },
                            onDeleteDownloadFile = {
                                val deleted = onDeleteDownloadFile(record)
                                feedback = if (deleted) "تم حذف الملف." else "تعذر حذف الملف."
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("إزالة من السجل؟") },
            text = {
                Text(
                    "سيُزال هذا العنصر من سجل AHDownload. لن يُحذف الملف المكتمل من الجهاز.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteHistory(record)
                        pendingDelete = null
                        feedback = "تمت إزالة العنصر من السجل."
                    },
                ) {
                    Text("إزالة")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("إلغاء")
                }
            },
        )
    }
}

@Composable
private fun EmptyDownloads(
    modifier: Modifier,
    onGoHome: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("لا توجد تنزيلات بعد", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "عندما تبدأ تنزيلًا سيظهر هنا مع حالته وتقدمه وإجراءات التحكم.",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoHome) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("بدء تنزيل")
        }
    }
}

@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDeleteHistory: () -> Unit,
    onOpenDownload: () -> Unit,
    onShareDownload: () -> Unit,
    onDeleteDownloadFile: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val progress = record.totalBytes
        ?.takeIf { it > 0 }
        ?.let { (record.bytesDownloaded.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    val title = record.task.displayName?.takeIf { it.isNotBlank() }
        ?: record.task.destinationPath.substringAfterLast(File.separatorChar)

    val statusText = statusLabel(record.status)
    val statusIcon = when (record.status) {
        DownloadStatus.COMPLETED -> Icons.Rounded.CheckCircle
        DownloadStatus.FAILED -> Icons.Rounded.Refresh
        DownloadStatus.PAUSED -> Icons.Rounded.Pause
        DownloadStatus.CANCELLED -> Icons.Rounded.Cancel
        else -> Icons.Rounded.Download
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (record.status == DownloadStatus.COMPLETED) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else null,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                MediaThumbnail(
                    url = record.task.thumbnailUrl,
                    kind = record.task.mediaKind,
                    modifier = Modifier
                        .size(width = 94.dp, height = 68.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentDescription = "صورة مصغرة: " + title,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = statusColor(record.status),
                        )
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor(record.status),
                        )
                    }
                    val kindText = record.task.mediaKind?.let(::kindLabel)
                    if (kindText != null) {
                        Text(
                            kindText + " · " + extensionLabel(title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            record.updatedAtEpochMs,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "المزيد")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (record.status == DownloadStatus.COMPLETED && record.destinationUri != null) {
                            DropdownMenuItem(
                                text = { Text("فتح") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenDownload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("مشاركة") },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onShareDownload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("حذف الملف") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteDownloadFile()
                                },
                            )
                        }
                        if (record.status !in ACTIVE_STATUSES) {
                            DropdownMenuItem(
                                text = { Text("إزالة من السجل") },
                                leadingIcon = { Icon(Icons.Rounded.Clear, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteHistory()
                                },
                            )
                        }
                    }
                }
            }

            if (progress != null && record.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    (progress * 100).toInt().toString() + "% · " +
                        formatBytes(record.bytesDownloaded) + " / " +
                        formatBytes(record.totalBytes ?: record.bytesDownloaded),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (record.bytesDownloaded > 0L) {
                Text(
                    formatBytes(record.bytesDownloaded) +
                        (record.totalBytes?.let { " / " + formatBytes(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                destinationLabel(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (record.status) {
                    DownloadStatus.QUEUED,
                    DownloadStatus.PREPARING,
                    DownloadStatus.DOWNLOADING -> {
                        OutlinedButton(onClick = onPause) {
                            Icon(Icons.Rounded.Pause, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إيقاف مؤقت")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        Button(onClick = onResume) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("استئناف")
                        }
                        TextButton(onClick = onCancel) { Text("إلغاء نهائي") }
                    }
                    DownloadStatus.CANCELLED -> {
                        Button(onClick = onResume) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إعادة التنزيل")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Button(onClick = onRetry) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إعادة المحاولة")
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        if (record.destinationUri != null) {
                            Button(onClick = onOpenDownload) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("فتح")
                            }
                            OutlinedButton(onClick = onShareDownload) {
                                Icon(Icons.Rounded.Share, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("مشاركة")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    url: String?,
    kind: MediaKind?,
    modifier: Modifier,
    contentDescription: String,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (url.isNullOrBlank()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    when (kind) {
                        MediaKind.Video -> Icons.Rounded.VideoFile
                        MediaKind.Audio -> Icons.Rounded.AudioFile
                        MediaKind.Image -> Icons.Rounded.Image
                        else -> Icons.Rounded.FolderOpen
                    },
                    contentDescription = contentDescription,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "في قائمة الانتظار"
    DownloadStatus.PREPARING -> "جاري التجهيز"
    DownloadStatus.DOWNLOADING -> "جاري التنزيل"
    DownloadStatus.PAUSED -> "متوقف مؤقتًا"
    DownloadStatus.COMPLETED -> "اكتمل التنزيل"
    DownloadStatus.FAILED -> "فشل التنزيل"
    DownloadStatus.CANCELLED -> "تم إلغاء التنزيل"
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun destinationLabel(record: DownloadRecord): String = when {
    record.destinationUri?.startsWith("content://") == true -> "محفوظ في مجلد الجهاز"
    record.destinationUri != null -> "محفوظ على الجهاز"
    else -> "مسار داخلي مؤقت"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index.coerceAtLeast(0)])
}

private fun extensionLabel(name: String): String =
    name.substringAfterLast('.', "").uppercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "FILE"

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Video -> "فيديو"
    MediaKind.Audio -> "صوت"
    MediaKind.Image -> "صورة"
    MediaKind.Unknown -> "ملف"
}

private val ACTIVE_STATUSES = setOf(
    DownloadStatus.QUEUED,
    DownloadStatus.PREPARING,
    DownloadStatus.DOWNLOADING,
)
