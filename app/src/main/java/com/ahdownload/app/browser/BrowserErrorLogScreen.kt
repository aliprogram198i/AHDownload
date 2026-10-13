package com.ahdownload.app.browser

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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahdownload.core.designsystem.DiagnosticIconButton
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserErrorLogRoute(
    store: BrowserErrorLogStore,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf(store.list()) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val latestEntry = entries.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل أحداث المتصفح") },
                navigationIcon = {
                    DiagnosticIconButton(
                        trackingScreen = "BROWSER_ERRORS",
                        trackingId = "BROWSER_ERRORS.back",
                        trackingLabel = "رجوع",
                        disabledReason = "لا يوجد",
                        onClick = onBack,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    DiagnosticIconButton(
                        trackingScreen = "BROWSER_ERRORS",
                        trackingId = "BROWSER_ERRORS.copy",
                        trackingLabel = "نسخ سجل أخطاء المتصفح بالكامل",
                        disabledReason = "السجل فارغ",
                        enabled = entries.isNotEmpty(),
                        onClick = { clipboard.setText(AnnotatedString(store.exportText())) },
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ السجل بالكامل")
                    }
                    DiagnosticIconButton(
                        trackingScreen = "BROWSER_ERRORS",
                        trackingId = "BROWSER_ERRORS.refresh",
                        trackingLabel = "تحديث سجل المتصفح",
                        disabledReason = "لا يوجد",
                        onClick = { entries = store.list() },
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "تحديث")
                    }
                    DiagnosticIconButton(
                        trackingScreen = "BROWSER_ERRORS",
                        trackingId = "BROWSER_ERRORS.clear",
                        trackingLabel = "مسح سجل المتصفح",
                        disabledReason = "السجل فارغ",
                        enabled = entries.isNotEmpty(),
                        onClick = { showClearConfirmation = true },
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح السجل")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text("لا توجد أحداث مسجلة في المتصفح بعد.", fontWeight = FontWeight.SemiBold)
                Text(
                    "ستظهر هنا أحداث التنقل وتقدم التحميل ومؤشرات عرض الصفحة ونتائج فحص الخيارات والتنزيل.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "يعرض القسم الأول آخر حدث بالتفصيل. تُحفظ حتى 80 حدثًا. يُحفظ المضيف ومسار مختصر فقط؛ تُحذف معاملات الرابط والجزء النهائي وتُنقّح الأسرار.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                latestEntry?.let { entry ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("آخر حدث بالتفصيل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    entry.severity,
                                    fontWeight = FontWeight.Bold,
                                    color = if (entry.severity == "ERROR" || entry.severity == "WARNING") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                Text(entry.type, fontWeight = FontWeight.Bold)
                                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                                Text("المرحلة: ${entry.stage}", style = MaterialTheme.typography.bodyMedium)
                                Text("المضيف: ${entry.host}", style = MaterialTheme.typography.bodyMedium)
                                entry.safeUrl?.let { Text("الرابط بعد التنقيح: $it", style = MaterialTheme.typography.bodySmall) }
                                entry.pageTitle?.let { Text("عنوان الصفحة: $it", style = MaterialTheme.typography.bodySmall) }
                                entry.details.toSortedMap()
                                    .filterNot { (key, _) ->
                                        (key == "document_height" && entry.details.containsKey("document_height_css_px")) ||
                                            (key == "viewport" && entry.details.containsKey("viewport_css_px"))
                                    }
                                    .forEach { (key, value) ->
                                        Text(
                                            "${diagnosticLabel(key)}: ${diagnosticValue(key, value)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                entry.code?.let { Text("رمز الخطأ: $it", style = MaterialTheme.typography.bodyMedium) }
                                Text(
                                    DateFormat.getDateTimeInstance().format(Date(entry.timestampEpochMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { clipboard.setText(AnnotatedString(store.exportEntryText(entry))) },
                                ) { Text("نسخ تفاصيل آخر خطأ") }
                            }
                        }
                    }
                }
                if (entries.size > 1) {
                    item {
                        Text("الأحداث السابقة (${entries.size - 1})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    items(entries.drop(1)) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("${entry.severity} • ${entry.type}", fontWeight = FontWeight.SemiBold)
                                Text(entry.message, style = MaterialTheme.typography.bodySmall)
                                entry.safeUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(
                                    "${entry.stage} • ${entry.host}" + (entry.code?.let { " • $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    DateFormat.getDateTimeInstance().format(Date(entry.timestampEpochMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
            title = { Text("مسح سجل المتصفح؟") },
            text = { Text("سيتم حذف أحداث الأخطاء المسجلة لهذا المتصفح فقط.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        entries = emptyList()
                        showClearConfirmation = false
                    },
                ) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("إلغاء") }
            },
        )
    }
}

private fun diagnosticLabel(key: String): String = when (key) {
    "navigation_id" -> "معرّف التنقل"
    "navigation_source" -> "مصدر التنقل"
    "is_redirect" -> "إعادة توجيه"
    "http_method" -> "طريقة الطلب"
    "initiator" -> "مصدر الانتقال"
    "progress_percent" -> "تقدم التحميل"
    "elapsed_ms" -> "المدة (مللي ثانية)"
    "render_status" -> "تقييم ظهور المحتوى"
    "page_render_status_at_event" -> "حالة العرض وقت الحدث"
    "ready_state" -> "حالة المستند"
    "body_text_chars" -> "عدد أحرف النص"
    "body_child_count" -> "عدد عناصر الصفحة"
    "document_height" -> "ارتفاع المستند"
    "document_height_css_px" -> "ارتفاع المستند (CSS px)"
    "viewport" -> "حجم نافذة العرض"
    "viewport_css_px" -> "أبعاد نافذة العرض (CSS px)"
    "device_pixel_ratio" -> "نسبة كثافة العرض"
    "webview_width_px" -> "عرض WebView الفعلي (px)"
    "webview_height_px" -> "ارتفاع WebView الفعلي (px)"
    "video_count" -> "عدد عناصر الفيديو"
    "audio_count" -> "عدد عناصر الصوت"
    "image_count" -> "عدد الصور"
    "visible_image_count" -> "الصور الظاهرة بعد فحص الطبقة العلوية"
    "viewport_intersecting_image_count" -> "الصور المتقاطعة مع نافذة العرض"
    "hit_test_rejected_image_count" -> "الصور المرفوضة بسبب فحص الطبقة العلوية"
    "visible_large_image_count" -> "الصور الكبيرة الظاهرة"
    "visible_image_area_ratio" -> "نسبة مساحة الصور الظاهرة (من 0 إلى 1)"
    "visible_text_chars" -> "أحرف النص الظاهرة بعد فحص الطبقة العلوية"
    "viewport_intersecting_text_node_count" -> "عُقد النص المتقاطعة مع نافذة العرض"
    "hit_test_rejected_text_node_count" -> "عُقد النص المرفوضة بسبب فحص الطبقة العلوية"
    "readable_visible_text_chars" -> "أحرف النص المقروءة ذات التباين"
    "recheck_delay_ms" -> "مهلة إعادة الفحص (مللي ثانية)"
    "visible_media_element_count" -> "عناصر الوسائط المرئية"
    "visible_content_element_count" -> "الحاويات الدلالية/الوسائط المرئية"
    "has_visible_content_signals" -> "رُصدت إشارات محتوى مرئية"
    "visible_content_signal_sources" -> "مصادر الإشارات المرئية"
    "visible_content_element_scope" -> "نطاق عدّ عناصر المحتوى"
    "content_visibility_basis" -> "أساس تقدير الظهور"
    "iframe_count" -> "عدد الإطارات"
    "background_color" -> "لون خلفية الصفحة"
    "challenge_detected" -> "مؤشرات تحقق أو حظر"
    "http_resource_errors" -> "أخطاء موارد HTTP"
    "http_status" -> "رمز HTTP"
    "is_main_frame" -> "خطأ في الصفحة الرئيسية"
    "operation_id" -> "معرّف الفحص"
    "candidate_count" -> "عدد المصادر المكتشفة"
    "video_option_count" -> "خيارات الفيديو"
    "audio_source_count" -> "مصادر الصوت"
    "resolver_outcome" -> "نتيجة المحلل"
    "failure_code" -> "رمز الفشل"
    "reason" -> "السبب"
    "result" -> "نتيجة الفحص"
    "console_line" -> "سطر JavaScript"
    "console_source_host" -> "مضيف مصدر JavaScript"
    "console_message_level" -> "مستوى رسالة JavaScript"
    "console_message" -> "تفاصيل خطأ JavaScript"
    "page_progress_percent" -> "تقدم الصفحة عند الفحص"
    "page_title_known" -> "عنوان الصفحة متوفر"
    "resource_error_index" -> "رقم خطأ المورد"
    "exception_type" -> "نوع الاستثناء"
    "validation_failure" -> "سبب فشل التحقق"
    "candidate_id" -> "معرّف مصدر الوسائط"
    "format" -> "صيغة الوسائط"
    "has_audio" -> "يحتوي صوتًا"
    "has_video" -> "يحتوي فيديو"
    "enqueue_outcome" -> "نتيجة إدراج التنزيل"
    "output_format" -> "صيغة الصوت"
    else -> key
}

private fun diagnosticValue(key: String, value: String): String = when (key) {
    "visible_content_signal_sources" -> value.split(",")
        .filter { it.isNotBlank() }
        .joinToString("، ") {
            when (it) {
                "visible_text" -> "نص مرئي"
                "visible_images" -> "صور مرئية"
                "visible_media" -> "وسائط مرئية"
                else -> it
            }
        }.ifBlank { "لا توجد إشارات" }
    "visible_content_element_scope" ->
        "يحصي الحاويات الدلالية والوسائط القابلة للرسم فقط؛ ولا يحصي النصوص المنفردة أو الصور وحدها"
    "content_visibility_basis" ->
        "تقاطع العناصر مع نافذة العرض، وفحص تباين النص والتأكد من عدم حجب العناصر بعنصر آخر"
    else -> value
}
