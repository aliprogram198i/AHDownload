package com.ahdownload.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHBottomNavDestination
import com.ahdownload.core.designsystem.AHBottomNavigationBar
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import java.util.Locale

@Composable
fun DownloadsRoute(
    repository: DownloadRepository,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (DownloadRecord) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
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
    val vm: DownloadsViewModel = viewModel(
        factory = DownloadsViewModel.Factory(repository, controls),
    )
    val records by vm.records.collectAsStateWithLifecycle()

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(records) {
        val active = records.count {
            it.status in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.PREPARING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
            )
        }
        uiTraceLogger.snapshot(
            screen = "DOWNLOADS",
            component = "DownloadsScreen",
            components = if (records.isEmpty()) {
                "topbar,empty_state,bottom_navigation"
            } else {
                "topbar,history_filters,history_list,download_cards,bottom_navigation"
            },
            stateSummary = "records=" + records.size +
                ";active=" + active +
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
        onOpenDownload = onOpenDownload,
        uiTraceLogger = uiTraceLogger,
    )
}

private enum class DownloadFilter { ALL, ACTIVE, COMPLETED, FAILED }

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
    onOpenDownload: (DownloadRecord) -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    val sortedRecords = remember(records) { records.sortedByDescending { it.updatedAtEpochMs } }
    val activeCount = remember(sortedRecords) {
        sortedRecords.count {
            it.status in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.PREPARING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
            )
        }
    }
    val completedCount = remember(sortedRecords) { sortedRecords.count { it.status == DownloadStatus.COMPLETED } }
    val failedCount = remember(sortedRecords) { sortedRecords.count { it.status == DownloadStatus.FAILED } }
    val visible = remember(sortedRecords, filter) {
        sortedRecords.filter {
            when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> it.status in setOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.PREPARING,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.PAUSED,
                )
                DownloadFilter.COMPLETED -> it.status == DownloadStatus.COMPLETED
                DownloadFilter.FAILED -> it.status == DownloadStatus.FAILED
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التنزيلات") },
                navigationIcon = {
                    IconButton(onClick = {
                        uiTraceLogger.interaction("DOWNLOADS", "back_button", "back")
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
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
            EmptyDownloadsState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onBrowse = onNavigateHome,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("مدير التنزيلات", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "تابع الحالات النشطة والمكتملة وأعد المحاولة عند الحاجة.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = filter == DownloadFilter.ALL,
                                onClick = { filter = DownloadFilter.ALL },
                                label = { Text("الكل " + records.size) },
                            )
                        }
                        item {
                            FilterChip(
                                selected = filter == DownloadFilter.ACTIVE,
                                onClick = { filter = DownloadFilter.ACTIVE },
                                label = { Text("نشطة " + activeCount) },
                            )
                        }
                        item {
                            FilterChip(
                                selected = filter == DownloadFilter.COMPLETED,
                                onClick = { filter = DownloadFilter.COMPLETED },
                                label = { Text("مكتملة " + completedCount) },
                            )
                        }
                        item {
                            FilterChip(
                                selected = filter == DownloadFilter.FAILED,
                                onClick = { filter = DownloadFilter.FAILED },
                                label = { Text("فاشلة " + failedCount) },
                            )
                        }
                    }
                }

                if (visible.isEmpty()) {
                    item {
                        EmptyDownloadsState(
                            modifier = Modifier.fillMaxWidth(),
                            onBrowse = onNavigateHome,
                            compact = true,
                        )
                    }
                } else {
                    items(visible, key = { it.task.id }) { record ->
                        DownloadRecordCard(
                            record = record,
                            onPause = { onPause(record) },
                            onResume = { onResume(record) },
                            onCancel = { onCancel(record) },
                            onRetry = { onRetry(record) },
                            onOpenDownload = { onOpenDownload(record) },
                            uiTraceLogger = uiTraceLogger,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsState(
    modifier: Modifier,
    onBrowse: () -> Unit,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Download,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (compact) "لا توجد نتائج لهذا التصنيف" else "لا توجد تنزيلات بعد",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            if (compact) "جرّب تصنيفًا آخر."
            else "ابدأ من الرئيسية بلصق رابط وسيظهر هنا مسار التنزيل كاملًا.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!compact) {
            Button(onClick = onBrowse, modifier = Modifier.padding(top = 14.dp)) {
                Text("العودة للرئيسية")
            }
        }
    }
}

@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    uiTraceLogger: UiTraceLogger,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenDownload: () -> Unit,
) {
    val progress = record.totalBytes?.takeIf { it > 0 }?.let {
        (record.bytesDownloaded.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    }
    val title = record.task.displayName?.takeIf { it.isNotBlank() }
        ?: record.task.destinationPath.substringAfterLast('/')

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLabel(record.status),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor(record.status),
                    )
                }
                AHStatusPill(formatKindLabel(record))
            }

            if (progress != null && record.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    (progress * 100).toInt().toString() + "% · " +
                        formatBytes(record.bytesDownloaded) + " / " +
                        (record.totalBytes?.let(::formatBytes) ?: ""),
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
                when {
                    record.destinationUri?.startsWith("content://") == true -> "محفوظ في تخزين الجهاز"
                    record.destinationUri != null -> "تم حفظ الملف بنجاح"
                    else -> "بانتظار إتمام الحفظ"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (record.status) {
                    DownloadStatus.QUEUED,
                    DownloadStatus.PREPARING,
                    DownloadStatus.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("DOWNLOADS", "pause_control", "pause")
                                onPause()
                            },
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null)
                            Text("إيقاف")
                        }
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("DOWNLOADS", "cancel_control", "cancel")
                                onCancel()
                            },
                        ) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        Button(
                            onClick = {
                                uiTraceLogger.interaction("DOWNLOADS", "resume_control", "resume")
                                onResume()
                            },
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("استئناف")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.CANCELLED -> {
                        Button(
                            onClick = {
                                uiTraceLogger.interaction("DOWNLOADS", "cancelled_retry_control", "redownload")
                                onResume()
                            },
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text("إعادة التنزيل")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Button(
                            onClick = {
                                uiTraceLogger.interaction("DOWNLOADS", "retry_control", "retry")
                                onRetry()
                            },
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text("إعادة المحاولة")
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        if (record.destinationUri != null) {
                            Button(
                                onClick = {
                                    uiTraceLogger.interaction("DOWNLOADS", "open_control", "open")
                                    onOpenDownload()
                                },
                            ) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                                Text("فتح")
                            }
                        }
                    }
                }
            }

            if (record.status == DownloadStatus.FAILED && !record.failureDetail.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        record.failureDetail ?: "تعذر إكمال التنزيل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatKindLabel(record: DownloadRecord): String = when (record.task.mediaKind?.name) {
    "Video" -> "فيديو"
    "Audio" -> "صوت"
    "Image" -> "صورة"
    else -> "ملف"
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "في قائمة الانتظار"
    DownloadStatus.PREPARING -> "جاري التجهيز"
    DownloadStatus.DOWNLOADING -> "جاري التنزيل"
    DownloadStatus.PAUSED -> "متوقف مؤقتًا"
    DownloadStatus.COMPLETED -> "اكتمل التنزيل"
    DownloadStatus.FAILED -> "فشل التنزيل"
    DownloadStatus.CANCELLED -> "تم الإلغاء"
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
    return String.format(Locale.US, "%.1f %s", value, units[index.coerceAtLeast(0)])
}
