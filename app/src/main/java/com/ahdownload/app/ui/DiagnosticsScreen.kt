package com.ahdownload.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahdownload.app.diagnostics.AppLogger

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(AppLogger.copyText(context)) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("سجل التطبيق", style = MaterialTheme.typography.headlineMedium)
        Text("سجل تشخيص حقيقي محفوظ محلياً. لا يتم إرسال السجل إلى خادم.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AHDownload diagnostic log", log))
            }) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("نسخ السجل")
            }
            OutlinedButton(onClick = {
                AppLogger.clear(context)
                log = AppLogger.copyText(context)
            }) {
                Icon(Icons.Default.DeleteSweep, null)
                Spacer(Modifier.width(6.dp))
                Text("مسح")
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxSize(), tonalElevation = 1.dp) {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item { Text(log, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
