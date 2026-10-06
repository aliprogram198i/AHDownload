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
    val exportText = remember(logs) { formatDiagnostics(logs) }
    val errors = logs.count { it.level == DiagnosticLevel.ERROR }
    val warnings = logs.count { it.level == DiagnosticLevel.WARNING }
    val infos = logs.count { it.level == DiagnosticLevel.INFO }
    val sessions = logs.mapNotNull { it.context["diagnostic_session_id"] }.distinct().size
    val operations = logs.mapNotNull { it.operation.takeIf(String::isNotBlank) }.distinct().size
    Scaffold(topBar = {
        TopAppBar(title = { Text("سجل الأخطاء والتشخيص") }, navigationIcon = { IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) } }, actions = {
            IconButton(onClick = { clipboard.setText(AnnotatedString(exportText)) }, enabled = logs.isNotEmpty()) { Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ السجل الكامل") }
            IconButton(onClick = onClear, enabled = logs.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح السجل") }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("سجل مركزي واحد لكل عمليات التطبيق. تُحفظ الأحداث محليًا مع إخفاء بيانات الاعتماد وعدم حفظ استعلامات الروابط.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (logs.isNotEmpty()) {
                    Text("الإجمالي: ${logs.size}  •  أخطاء: $errors  •  تحذيرات: $warnings  •  معلومات: $infos", style = MaterialTheme.typography.labelLarge)
                    Text("الجلسات: $sessions  •  العمليات: $operations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            log.throwableStackTrace?.let {
                Text("StackTrace:", style = MaterialTheme.typography.labelMedium)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatDiagnostic(log: DiagnosticLog): String = buildString {
    appendLine("AHDownload Diagnostic")
    appendLine("id=" + log.id + " time=" + formatTime(log.timestampEpochMs))
    appendLine("level=" + log.level + " type=" + log.type + " operation=" + log.operation)
    appendLine("reason=" + log.reason)
    val contextKeys = listOf("app_package", "app_version_name", "app_version_code", "android_release", "android_sdk", "app_target_sdk", "device_manufacturer", "device_model", "diagnostic_session_id", "event_sequence", "source", "provider", "stage", "resolver", "status", "http_status", "content_type", "content_length", "duration_ms", "failure_code")
    contextKeys.forEach { key -> log.context[key]?.takeIf(String::isNotBlank)?.let { appendLine(key + "=" + it) } }
    log.throwableType?.let { appendLine("exception=" + it) }
    log.throwableMessage?.let { appendLine("detail=" + it) }
    log.throwableStackTrace?.let { appendLine("stack=" + it.lineSequence().take(12).joinToString(" <- ").take(2400)) }
}.trimEnd()
private fun formatDiagnostics(logs: List<DiagnosticLog>): String {
    if (logs.isEmpty()) return "AHDownload Diagnostic Report\nstatus=NO_LOGS"
    val latestError = logs.firstOrNull { it.level == DiagnosticLevel.ERROR }
    val sessionId = latestError?.context?.get("diagnostic_session_id")
    val related = if (sessionId != null) logs.filter { it.context["diagnostic_session_id"] == sessionId }.take(36) else logs.take(36)
    val anchor = latestError ?: related.first()
    return buildString {
        appendLine("AHDownload Diagnostic Report")
        appendLine("app=" + (anchor.context["app_package"] ?: "unknown") + " version=" + (anchor.context["app_version_name"] ?: "unknown") + " (" + (anchor.context["app_version_code"] ?: "unknown") + ")")
        appendLine("android=" + (anchor.context["android_release"] ?: "unknown") + " sdk=" + (anchor.context["android_sdk"] ?: "unknown") + " targetSdk=" + (anchor.context["app_target_sdk"] ?: "unknown"))
        appendLine("device=" + (anchor.context["device_manufacturer"] ?: "unknown") + " " + (anchor.context["device_model"] ?: "unknown"))
        appendLine("session=" + (sessionId ?: "unknown") + " events=" + related.size)
        appendLine("latest=" + formatTime(anchor.timestampEpochMs) + " level=" + anchor.level + " type=" + anchor.type + " operation=" + anchor.operation)
        appendLine("reason=" + anchor.reason)
        anchor.throwableType?.let { appendLine("exception=" + it) }
        anchor.throwableMessage?.let { appendLine("detail=" + it) }
        anchor.throwableStackTrace?.let { appendLine("stack=" + it.lineSequence().take(12).joinToString(" <- ").take(2400)) }
        appendLine("timeline:")
        related.asReversed().forEach { log ->
            val keys = listOf("source", "provider", "stage", "resolver", "candidate", "status", "http_status", "content_type", "content_length", "duration_ms", "attempt", "attempts", "result", "failure_code")
            val context = keys.mapNotNull { key -> log.context[key]?.takeIf(String::isNotBlank)?.let { key + "=" + it } }.joinToString(" ")
            appendLine((log.context["event_sequence"] ?: "-") + " | " + formatTime(log.timestampEpochMs) + " | " + log.level + " | " + log.type + " | " + log.operation + " | " + log.reason + if (context.isNotEmpty()) " | " + context else "")
        }
    }.trimEnd()
}
private fun formatTime(epochMs: Long): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
