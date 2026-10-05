package com.ahdownload.app.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(logger: PersistentDiagnosticLogger, onBack: () -> Unit) {
    var logs by remember { mutableStateOf(logger.list()) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { logs = logger.list() }
    DiagnosticsScreen(logs, clipboard, onBack, { logs = logger.list() }, { logger.clear(); logs = emptyList() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(logs: List<DiagnosticLog>, clipboard: ClipboardManager, onBack: () -> Unit, onRefresh: () -> Unit, onClear: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("سجل الأخطاء والتشخيص") }, navigationIcon = { IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) } }, actions = {
            IconButton(onClick = onClear, enabled = logs.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح السجل") }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("السجل محفوظ محليًا ويحتوي على نوع الخطأ وسببه والوقت ومرحلة التنفيذ. لا يتم حفظ الرابط الكامل.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (logs.isNotEmpty()) Button(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) { Text("تحديث") }
            }
            if (logs.isEmpty()) item { Text("لا توجد أخطاء مسجلة حاليًا.", style = MaterialTheme.typography.titleMedium) }
            items(logs, key = { it.id }) { log -> DiagnosticCard(log, clipboard) }
        }
    }
}

@Composable
private fun DiagnosticCard(log: DiagnosticLog, clipboard: ClipboardManager) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(if (log.level == DiagnosticLevel.ERROR) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(log.type, style = MaterialTheme.typography.titleMedium)
                    Text(formatTime(log.timestampEpochMs), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(formatDiagnostic(log))) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ سجل الخطأ") }
            }
            Text("السبب: " + log.reason)
            Text("العملية: " + log.operation, color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.context.forEach { (key, value) -> Text(key + ": " + value, style = MaterialTheme.typography.bodySmall) }
            log.throwableType?.let { Text("Exception: " + it, style = MaterialTheme.typography.bodySmall) }
            log.throwableMessage?.let { Text("تفاصيل: " + it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatDiagnostic(log: DiagnosticLog): String = buildString {
    appendLine("AHDownload Diagnostic Log")
    appendLine("ID: " + log.id)
    appendLine("Time: " + formatTime(log.timestampEpochMs))
    appendLine("Level: " + log.level)
    appendLine("Type: " + log.type)
    appendLine("Operation: " + log.operation)
    appendLine("Reason: " + log.reason)
    log.context.forEach { (key, value) -> appendLine(key + ": " + value) }
    log.throwableType?.let { appendLine("Exception: " + it) }
    log.throwableMessage?.let { appendLine("ExceptionMessage: " + it) }
}

private fun formatTime(epochMs: Long): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
