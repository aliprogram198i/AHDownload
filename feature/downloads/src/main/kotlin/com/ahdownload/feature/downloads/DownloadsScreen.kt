package com.ahdownload.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.domain.download.*
import java.util.Locale

@Composable
fun DownloadsRoute(
    repository: DownloadRepository,
    controls: DownloadControls,
    onBack: () -> Unit,
) {
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory(repository, controls))
    val records by vm.records.collectAsStateWithLifecycle()
    DownloadsScreen(
        records = records,
        onBack = onBack,
        onPause = vm::pause,
        onResume = vm::resume,
        onCancel = vm::cancel,
        onRetry = vm::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(
    records: List<DownloadRecord>,
    onBack: () -> Unit,
    onPause: (DownloadRecord) -> Unit,
    onResume: (DownloadRecord) -> Unit,
    onCancel: (DownloadRecord) -> Unit,
    onRetry: (DownloadRecord) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التنزيلات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "رجوع")
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
                    )
                }
            }
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
                record.task.destinationPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (record.status) {
                    DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Rounded.Pause, contentDescription = "إيقاف مؤقت")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.CANCELLED -> {
                        Button(onClick = onResume) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("استئناف")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Button(onClick = onRetry) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text("إعادة المحاولة")
                        }
                    }
                    DownloadStatus.COMPLETED -> Unit
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
