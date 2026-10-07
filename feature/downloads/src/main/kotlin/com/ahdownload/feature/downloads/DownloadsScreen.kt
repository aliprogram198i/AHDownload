package com.ahdownload.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.*
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
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory(repository, controls))
    val records by vm.records.collectAsStateWithLifecycle()

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(records) {
        val componentNames = buildList {
            add("topbar")
            if (records.isEmpty()) {
                add("empty_state")
            } else {
                add("history_list")
                add("download_cards")
                if (records.any { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING) }) add("pause_controls")
                if (records.any { it.status in setOf(DownloadStatus.PAUSED, DownloadStatus.CANCELLED) }) add("resume_controls")
                if (records.any { it.status == DownloadStatus.FAILED }) add("retry_controls")
                if (records.any { it.destinationUri != null }) add("open_controls")
            }
        }.joinToString(",")
        uiTraceLogger.snapshot(
            screen = "DOWNLOADS",
            component = "DownloadsScreen",
            components = componentNames,
            stateSummary = "records=" + records.size + ";active=" + records.count { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING) } +
                ";completed=" + records.count { it.status == DownloadStatus.COMPLETED } +
                ";failed=" + records.count { it.status == DownloadStatus.FAILED },
            context = uiContext,
        )
    }
    DownloadsScreen(
        records = records,
        onBack = onBack,
        onPause = vm::pause,
        onResume = vm::resume,
        onCancel = vm::cancel,
        onRetry = vm::retry,
        onOpenDownload = onOpenDownload,
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
    onOpenDownload: (DownloadRecord) -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التنزيلات") },
                navigationIcon = {
                    IconButton(onClick = { uiTraceLogger.interaction("DOWNLOADS", "back_button", "back"); onBack() }) {
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
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("لا توجد تنزيلات بعد", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "ستظهر هنا التنزيلات النشطة والمكتملة وسجل المحاولات السابقة.",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("إدارة التنزيلات", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "إيقاف واستئناف وإلغاء وإعادة المحاولة مع حفظ الحالة.",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(records, key = { it.task.id }) { record ->
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(statusLabel(record.status), style = MaterialTheme.typography.labelLarge, color = statusColor(record.status))
            if (progress != null && record.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
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
                destinationLabel(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (record.status) {
                    DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = { uiTraceLogger.interaction("DOWNLOADS", "pause_control", "pause"); onPause() }) {
                            Icon(Icons.Rounded.Pause, contentDescription = "إيقاف مؤقت")
                        }
                        OutlinedButton(onClick = { uiTraceLogger.interaction("DOWNLOADS", "cancel_control", "cancel"); onCancel() }) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.CANCELLED -> {
                        Button(onClick = { uiTraceLogger.interaction("DOWNLOADS", "resume_control", "resume"); onResume() }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("استئناف")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Button(onClick = { uiTraceLogger.interaction("DOWNLOADS", "retry_control", "retry"); onRetry() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text("إعادة المحاولة")
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        if (record.destinationUri != null) {
                            OutlinedButton(onClick = { uiTraceLogger.interaction("DOWNLOADS", "open_control", "open"); onOpenDownload() }) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                                Text("فتح")
                            }
                        }
                    }
                }
            }
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
    DownloadStatus.CANCELLED -> "تم الإلغاء"
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun destinationLabel(record: DownloadRecord): String = when {
    record.destinationUri?.startsWith("content://") == true -> "الوجهة النهائية: تخزين الجهاز"
    record.destinationUri != null -> "الوجهة النهائية: ${record.destinationUri}"
    else -> "المسار الداخلي: ${record.task.destinationPath}"
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
