package com.ahdownload.app.ui
import com.ahdownload.app.ui.theme.AHGradientButton

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahdownload.app.diagnostics.AppLogger

private fun buildDesignSnapshot(): String {
    return buildString {
        appendLine("AHDownload UI DESIGN SNAPSHOT")
        appendLine("generated_at=" + System.currentTimeMillis())
        appendLine("theme=AHDownloadTheme")
        appendLine("system=unified-gradient-interaction")
        appendLine("button.primary=AHGradientButton")
        appendLine("button.secondary=AHGradientOutlinedButton")
        appendLine("shape.primary=16dp")
        appendLine("shape.card=20-28dp")
        appendLine("screens=الرئيسية;التنزيلات;Smart Studio;الإعدادات;الحسابات;سجل التطبيق")
        appendLine("sensitive_data=excluded")
    }
}

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(AppLogger.copyText(context)) }
    var designSnapshot by remember { mutableStateOf(buildDesignSnapshot()) }
    fun refreshLog() { log = AppLogger.copyText(context); designSnapshot = buildDesignSnapshot() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("سجل التطبيق", style = MaterialTheme.typography.headlineMedium)
        Text("سجل تشخيص حقيقي محفوظ محلياً. لا يتم إرسال السجل إلى خادم.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refreshLog() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("تحديث")
                }
                AHGradientButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AHDownload diagnostic log", log))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(6.dp))
                    Text("نسخ السجل")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, log) }
                    context.startActivity(Intent.createChooser(share, "مشاركة سجل AHDownload"))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text("مشاركة")
                }
                OutlinedButton(onClick = {
                    AppLogger.clear(context)
                    refreshLog()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Spacer(Modifier.width(6.dp))
                    Text("مسح")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { refreshLog() },
                    label = { Text(if (log.isBlank()) "السجل فارغ" else "السجل متاح للتحليل") },
                    leadingIcon = { Icon(if (log.isBlank()) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxSize(), tonalElevation = 1.dp) {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item { Text(designSnapshot + "\n--- RUNTIME DIAGNOSTICS ---\n" + log, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
