package com.ahdownload.app.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.rememberUiTraceContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    logger: PersistentDiagnosticLogger,
    uiTraceLogger: UiTraceLogger,
    onBack: () -> Unit,
) {
    var logs by remember { mutableStateOf(logger.list()) }
    val clipboard = LocalClipboardManager.current
    val uiContext = rememberUiTraceContext()

    LaunchedEffect(Unit) { logs = logger.list() }
    LaunchedEffect(logs) {
        val latestError = logs.firstOrNull { it.level == DiagnosticLevel.ERROR }
        uiTraceLogger.snapshot(
            screen = "DIAGNOSTICS",
            component = "DiagnosticsScreen",
            components = "topbar,latest_error_summary,incident_report,refresh_button,copy_button,clear_button",
            stateSummary = "latest_error=" + (latestError != null) + ";stored_events=" + logs.size,
            context = uiContext,
        )
    }

    DiagnosticsScreen(
        logs = logs,
        clipboard = clipboard,
        uiTraceLogger = uiTraceLogger,
        onBack = onBack,
        onRefresh = {
            uiTraceLogger.interaction("DIAGNOSTICS", "refresh_button", "refresh")
            logs = logger.list()
        },
        onClear = {
            uiTraceLogger.interaction("DIAGNOSTICS", "clear_button", "clear")
            logger.clear()
            logs = emptyList()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    logs: List<DiagnosticLog>,
    clipboard: ClipboardManager,
    uiTraceLogger: UiTraceLogger,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    val latestError = remember(logs) { logs.firstOrNull { it.level == DiagnosticLevel.ERROR } }
    val report = remember(logs) { DiagnosticReportFormatter.format(logs, maxEvents = 120) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("آخر خطأ") },
                navigationIcon = {
                    IconButton(onClick = {
                        uiTraceLogger.interaction("DIAGNOSTICS", "back_button", "back")
                        onBack()
                    }) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            uiTraceLogger.interaction("DIAGNOSTICS", "copy_button", "copy_latest_incident")
                            clipboard.setText(AnnotatedString(report))
                        },
                        enabled = latestError != null,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ تفاصيل آخر خطأ")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "تحديث")
                    }
                    IconButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح التشخيص")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (latestError != null) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (latestError != null) Icons.Rounded.ErrorOutline else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (latestError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                if (latestError != null) "تم التقاط آخر حادثة فشل" else "لا توجد أخطاء مسجلة",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (latestError != null) "يُعرض هنا الخطأ الأخير فقط، مع كل الأحداث المرتبطة به قبل نقطة الفشل."
                                else "سجل الأحداث الداخلي موجود للتشخيص، لكن لا توجد حادثة خطأ أخيرة لعرضها.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (latestError != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("ملخص الخطأ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(latestError.type, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("السبب: " + latestError.reason)
                            Text("العملية: " + latestError.operation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            latestError.context["stage"]?.let { Text("المرحلة: " + it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            latestError.context["operation_id"]?.let { Text("معرّف العملية: " + it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("التقرير الكامل للحادثة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            SelectionContainer {
                                Text(
                                    report,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}