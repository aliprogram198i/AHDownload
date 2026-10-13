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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل أخطاء المتصفح") },
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
                        trackingLabel = "نسخ سجل أخطاء المتصفح",
                        disabledReason = "السجل فارغ",
                        enabled = entries.isNotEmpty(),
                        onClick = { clipboard.setText(AnnotatedString(store.exportText())) },
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "نسخ السجل")
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
                Text("لا توجد أخطاء مسجلة في المتصفح.", fontWeight = FontWeight.SemiBold)
                Text(
                    "ستظهر هنا أخطاء تحميل الصفحات أو الفحص أو التحقق من اتصال آمن.",
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
                        "آخر ${entries.size} حدث كحد أقصى. لا يُحفظ عنوان الصفحة الكامل أو ملفات تعريف الارتباط أو رموز الجلسة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries, key = { "${it.timestampEpochMs}-${it.type}-${it.stage}" }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(entry.type, fontWeight = FontWeight.Bold)
                            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "المرحلة: ${entry.stage} • المضيف: ${entry.host}" +
                                    (entry.code?.let { " • الرمز: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
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
