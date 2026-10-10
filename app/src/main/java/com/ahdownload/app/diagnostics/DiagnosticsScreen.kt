package com.ahdownload.app.diagnostics

import com.ahdownload.core.designsystem.DiagnosticButton
import com.ahdownload.core.designsystem.DiagnosticOutlinedButton
import com.ahdownload.core.designsystem.DiagnosticIconButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
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

    LaunchedEffect(Unit) {
        logs = logger.list()
    }

    LaunchedEffect(logs) {
        val latestError = logs.firstOrNull { it.level == DiagnosticLevel.ERROR }
        uiTraceLogger.snapshot(
            screen = "DIAGNOSTICS",
            component = "DiagnosticsScreen",
            components = "topbar,incident_summary,pipeline,technical_details,refresh_button,copy_button,clear_button",
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
    var showTechnical by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val rootCause = remember(report) { reportValue(report, "root_cause") }
    val classification = remember(report) { reportValue(report, "classification") }
    val stage = remember(report) { reportValue(report, "stage") }
    val action = remember(report) { reportValue(report, "action") }
    val whatHappened = remember(report) { reportValue(report, "what_happened") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل الأخطاء") },
                navigationIcon = {
                    DiagnosticIconButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.iconbutton.01",
            trackingLabel = "iconbutton_control",
            disabledReason = "callsite_precondition_not_explicit",onClick = {
                        uiTraceLogger.interaction("DIAGNOSTICS", "back_button", "back")
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    DiagnosticIconButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.iconbutton.02",
            trackingLabel = "iconbutton_control",
            disabledReason = "callsite_precondition_not_explicit",
                        onClick = {
                            uiTraceLogger.interaction("DIAGNOSTICS", "copy_button", "copy_latest_incident")
                            clipboard.setText(AnnotatedString(report))
                        },
                        enabled = latestError != null,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ التقرير")
                    }
                    DiagnosticIconButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.iconbutton.03",
            trackingLabel = "iconbutton_control",
            disabledReason = "callsite_precondition_not_explicit",onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "تحديث")
                    }
                    DiagnosticIconButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.iconbutton.04",
            trackingLabel = "iconbutton_control",
            disabledReason = "callsite_precondition_not_explicit",
                        onClick = { showClearConfirmation = true },
                        enabled = logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح السجل")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (latestError != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (latestError != null) {
                                Icons.Rounded.ErrorOutline
                            } else {
                                Icons.Rounded.CheckCircle
                            },
                            contentDescription = null,
                            tint = if (latestError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                if (latestError != null) "آخر حادثة فشل"
                                else "لا توجد أخطاء",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (latestError != null) {
                                    "يعرض التطبيق آخر خطأ فقط في الواجهة، مع إبقاء التقرير التقني متاحًا عند الحاجة."
                                } else {
                                    "لم يتم تسجيل حادثة فشل قابلة للعرض حتى الآن."
                                },
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
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                userFriendlyTitle(rootCause, latestError.type),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                whatHappened.ifBlank { latestError.reason },
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            SummaryRow("المرحلة", userFriendlyStage(stage.ifBlank { latestError.context["stage"] ?: "-" }))
                            SummaryRow("السبب", userFriendlyRootCause(rootCause.ifBlank { latestError.type }))
                            SummaryRow("التصنيف", userFriendlyClassification(classification))
                            SummaryRow("الإجراء المقترح", userFriendlyAction(action))
                            SummaryRow("وقت الخطأ", formatDiagnosticTime(latestError.timestampEpochMs))
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "تسلسل العملية",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            PipelineLine("التحليل", reportValue(report, "analysis"))
                            PipelineLine("المصدر", reportValue(report, "resolution"))
                            PipelineLine("التحقق", reportValue(report, "media_validation"))
                            PipelineLine("التنزيل", reportValue(report, "download"))
                            PipelineLine("معالجة الصوت", reportValue(report, "audio_processing"))
                            PipelineLine("التخزين", reportValue(report, "storage"))
                        }
                    }
                }

                item {
                    DiagnosticOutlinedButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.outlinedbutton.01",
            trackingLabel = "outlinedbutton_control",
            disabledReason = "callsite_precondition_not_explicit",
                        onClick = {
                            showTechnical = !showTechnical
                            uiTraceLogger.interaction(
                                "DIAGNOSTICS",
                                "technical_details",
                                if (showTechnical) "collapse" else "expand",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            if (showTechnical) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                        )
                        Text(if (showTechnical) "إخفاء التفاصيل التقنية" else "عرض التفاصيل التقنية")
                    }
                }

                if (showTechnical) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "التقرير التقني",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "يتضمن بيانات التشخيص الضرورية للمراجعة، مع تطبيق التنقية على البيانات الحساسة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("مسح سجل الأخطاء؟") },
            text = {
                Text("سيتم حذف السجل المحلي للتشخيص من هذا الجهاز. لا يؤثر ذلك على الملفات أو التنزيلات.")
            },
            confirmButton = {
                DiagnosticButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.button.01",
            trackingLabel = "button_control",
            disabledReason = "callsite_precondition_not_explicit",
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                ) {
                    Text("مسح")
                }
            },
            dismissButton = {
                DiagnosticOutlinedButton(
            trackingScreen = "DIAGNOSTICS",
            trackingId = "DIAGNOSTICS.outlinedbutton.02",
            trackingLabel = "outlinedbutton_control",
            disabledReason = "callsite_precondition_not_explicit",onClick = { showClearConfirmation = false }) {
                    Text("إلغاء")
                }
            },
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PipelineLine(label: String, value: String) {
    val completed = value.equals("COMPLETED", ignoreCase = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (completed) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (completed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                pipelineStateLabel(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun reportValue(report: String, key: String): String =
    report.lineSequence()
        .firstOrNull { it.startsWith(key + "=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()

private fun pipelineStateLabel(value: String): String =
    when (value) {
        "COMPLETED" -> "اكتملت"
        "FAILED" -> "فشلت"
        "NOT_STARTED" -> "لم تبدأ"
        "STARTED" -> "قيد التنفيذ"
        else -> value.ifBlank { "غير معروف" }
    }

private fun userFriendlyTitle(rootCause: String, fallbackType: String): String =
    when {
        rootCause == "AUDIO_EXTRACTION_FAILED" -> "فشل استخراج الصوت"
        rootCause == "STORAGE_ERROR" -> "تعذر حفظ الملف"
        rootCause.startsWith("HTTP_403") -> "المصدر رفض الطلب"
        rootCause.startsWith("HTTP_") -> "خطأ في الاتصال بالمصدر"
        rootCause == "MEDIA_VALIDATION_FAILED" -> "لم يتم العثور على مصدر وسائط صالح"
        else -> fallbackType.ifBlank { "تعذر إكمال العملية" }
    }

private fun userFriendlyStage(value: String): String =
    when (value) {
        "HOME" -> "التحليل"
        "RESOLUTION" -> "استخراج المصدر"
        "MEDIA_VALIDATION" -> "التحقق من الوسائط"
        "DOWNLOAD" -> "التنزيل"
        "SMART_CENTER" -> "عرض النتائج"
        "STORAGE" -> "التخزين"
        else -> value.ifBlank { "غير محددة" }
    }

private fun userFriendlyRootCause(value: String): String =
    when {
        value == "AUDIO_EXTRACTION_FAILED" -> "معالجة الصوت"
        value == "STORAGE_ERROR" -> "التخزين"
        value == "MEDIA_VALIDATION_FAILED" -> "التحقق من الوسائط"
        value == "HTTP_403" -> "الخادم رفض الطلب (403)"
        value.startsWith("HTTP_") -> "خطأ HTTP أثناء الوصول للمصدر"
        value == "NONE" -> "لا يوجد"
        else -> value
    }

private fun userFriendlyClassification(value: String): String =
    when (value) {
        "AUDIO_PROCESSING" -> "معالجة الصوت"
        "STORAGE" -> "التخزين"
        "NETWORK" -> "الشبكة / المصدر"
        "UI_FLOW" -> "واجهة المستخدم"
        "RESOLUTION" -> "استخراج المصدر"
        else -> value.ifBlank { "غير محدد" }
    }

private fun userFriendlyAction(value: String): String =
    when (value) {
        "INSPECT_AUDIO_PROCESSOR" -> "تحقق من معالج الصوت أو جرّب صيغة صوت أخرى."
        "INSPECT_STORAGE" -> "تحقق من مجلد التنزيل والمساحة والصلاحيات."
        "INSPECT_BROWSER_MEDIA_CAPTURE" -> "أعد التحقق من جلسة المصدر أو أعد المحاولة."
        "INSPECT_MEDIA_VALIDATION" -> "أعد التحليل وتحقق من المصدر المتاح."
        "NONE" -> "لا يوجد إجراء مطلوب."
        else -> value.ifBlank { "أعد المحاولة بعد التحقق من المصدر." }
    }

private fun formatDiagnosticTime(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))
