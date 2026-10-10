package com.ahdownload.app.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceLogRoute(
    store: PerformanceLogStore,
    onBack: () -> Unit,
) {
    val entries by store.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val analyses = entries.filter { it.kind == "ANALYSIS" && it.outcome == "SUCCESS" }
    val downloads = entries.filter { it.kind == "DOWNLOAD" && it.outcome == "COMPLETED" }
    val averageAnalysisMs = analyses.map { it.durationMs }.averageOrNull()
    val averageDownloadSpeed = downloads.map { it.averageBytesPerSecond }.filter { it > 0L }.averageOrNull()
    val peakSpeed = entries.filter { it.kind == "DOWNLOAD" }.maxOfOrNull { it.peakBytesPerSecond } ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأداء وسجل السرعة") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "يسجل هذا القسم زمن تحليل الروابط وسرعة نقل البيانات، بشكل منفصل عن سجل الأخطاء. لا تُحفظ الروابط أو ملفات تعريف الارتباط أو رموز الجلسات.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        title = "متوسط التحليل",
                        value = averageAnalysisMs?.let { formatDuration(it.toLong()) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        title = "متوسط سرعة التنزيل",
                        value = averageDownloadSpeed?.let { PerformanceMetrics.formatSpeed(it.toLong()) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                MetricCard(
                    title = "أعلى سرعة مسجلة",
                    value = PerformanceMetrics.formatSpeed(peakSpeed),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(store.exportReport()))
                            Toast.makeText(context, "تم نسخ سجل الأداء", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Text(" نسخ التقرير")
                    }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        Text(" مسح السجل")
                    }
                }
            }
            item {
                Text(
                    "العمليات الأخيرة (${entries.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (entries.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("لا توجد بيانات أداء بعد", fontWeight = FontWeight.SemiBold)
                            Text(
                                "حلّل رابطًا أو نفّذ تنزيلًا لتظهر النتائج هنا.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    PerformanceEntryCard(entry)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح سجل الأداء؟") },
            text = { Text("سيتم حذف قياسات الأداء المحلية فقط. لن يتأثر سجل التنزيلات أو الملفات المحفوظة.") },
            confirmButton = {
                Button(onClick = {
                    store.clear()
                    confirmClear = false
                }) { Text("مسح") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmClear = false }) { Text("إلغاء") }
            },
        )
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PerformanceEntryCard(entry: PerformanceLogEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (entry.kind == "ANALYSIS") "تحليل رابط" else "نقل ملف",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${entry.platform} · ${entry.mediaKind}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    outcomeLabel(entry.outcome),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (entry.outcome == "SUCCESS" || entry.outcome == "COMPLETED") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                formatTimestamp(entry.completedAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("المدة: ${formatDuration(entry.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                entry.candidateCount?.let { Text("الخيارات: $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (entry.kind == "DOWNLOAD") {
                Text(
                    "المتوسط: ${PerformanceMetrics.formatSpeed(entry.averageBytesPerSecond)} · الذروة: ${PerformanceMetrics.formatSpeed(entry.peakBytesPerSecond)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                entry.responseMs?.let {
                    Text("زمن استجابة المصدر: ${formatDuration(it)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "البيانات: ${formatBytes(entry.bytesDownloaded)} / ${entry.totalBytes?.let(::formatBytes) ?: "غير معروف"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun List<Double>.averageOrNull(): Double? = takeIf { it.isNotEmpty() }?.average()

private fun outcomeLabel(outcome: String): String = when {
    outcome == "SUCCESS" -> "نجح التحليل"
    outcome == "EMPTY" -> "لا توجد خيارات"
    outcome == "INVALID_URL" -> "رابط غير صالح"
    outcome == "CANCELLED" -> "أُلغي"
    outcome == "PAUSED" -> "مؤقت"
    outcome == "COMPLETED" -> "مكتمل"
    outcome.startsWith("FAILED") -> "فشل"
    else -> outcome
}

private fun formatDuration(durationMs: Long): String = when {
    durationMs < 1_000L -> "${durationMs} ms"
    durationMs < 60_000L -> String.format(Locale.getDefault(), "%.2f s", durationMs / 1_000.0)
    else -> String.format(Locale.getDefault(), "%d min %02d s", durationMs / 60_000L, (durationMs % 60_000L) / 1_000L)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.getDefault(), "%.2f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

private fun formatTimestamp(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
