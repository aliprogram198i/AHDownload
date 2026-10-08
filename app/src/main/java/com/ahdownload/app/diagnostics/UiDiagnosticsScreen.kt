package com.ahdownload.app.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.rememberUiTraceContext
import androidx.compose.foundation.text.selection.SelectionContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiDiagnosticsRoute(
    logger: PersistentUiTraceLogger,
    onBack: () -> Unit,
) {
    var events by remember { mutableStateOf(logger.list()) }
    LaunchedEffect(Unit) { events = logger.list() }
    val clipboard = LocalClipboardManager.current
    val uiContext = rememberUiTraceContext()
    val report = remember(events) { logger.exportText() }
    val homeReport = remember(events) { logger.exportHomeText() }
    LaunchedEffect(events) {
        logger.snapshot(
            screen = "UI_DIAGNOSTICS",
            component = "UiDiagnosticsScreen",
            components = "topbar,summary,copy_button,refresh_button,clear_button,report_text",
            stateSummary = "events=" + events.size + ";report_length=" + report.length + ";home_report_length=" + homeReport.length,
            context = uiContext,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل الواجهة المتقدم") },
                navigationIcon = {
                    IconButton(onClick = { logger.interaction("UI_DIAGNOSTICS", "back_button", "back"); onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { logger.interaction("UI_DIAGNOSTICS", "refresh_button", "refresh"); events = logger.list() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "تحديث")
                    }
                    IconButton(onClick = { logger.interaction("UI_DIAGNOSTICS", "clear_button", "clear"); logger.clear(); events = emptyList() }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح سجل الواجهة")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null)
                            Text("UI Evidence", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            "يسجل ما ظهر على الشاشة، حالة المكونات، التفاعلات، الأخطاء، مقاسات العرض، الثيم، وألوان التصميم الرئيسية. لا يسجل كلمات المرور أو الكوكيز أو التوكنات أو استعلامات الروابط.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("الأحداث: ${events.size}", style = MaterialTheme.typography.labelLarge)
                        Text("الجلسة الحالية: ${events.firstOrNull()?.sessionId ?: "لا توجد"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Button(
                    onClick = { logger.interaction("UI_DIAGNOSTICS", "copy_button", "copy_full_report"); clipboard.setText(AnnotatedString(report)) },
                    enabled = report.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text("نسخ التقرير الكامل")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        logger.interaction("UI_DIAGNOSTICS", "copy_home_report_button", "copy_home_report")
                        clipboard.setText(AnnotatedString(homeReport))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text("نسخ سجل الشاشة الرئيسية")
                }
            }
            item {
                OutlinedButton(
                    onClick = { logger.interaction("UI_DIAGNOSTICS", "refresh_button", "refresh_report"); events = logger.list() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("تحديث التقرير")
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            report,
                            modifier = Modifier.padding(14.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}