package com.ahdownload.app.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogScreen(
    entries: List<DiagnosticLogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val exportText = remember(entries) {
        entries.joinToString("\n\n") { entry ->
            buildString {
                append("AHDownload Diagnostic Log\n")
                append("Time: ").append(Instant.ofEpochMilli(entry.timestampEpochMs)).append('\n')
                append("Type: ").append(entry.type).append('\n')
                append("Reason: ").append(entry.reason).append('\n')
                entry.detail?.let { append("Detail: ").append(it).append('\n') }
            }.trimEnd()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل الأخطاء") },
                navigationIcon = { OutlinedButton(onClick = onBack) { Text("رجوع") } },
                actions = {
                    OutlinedButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { clipboard.setText(AnnotatedString(exportText)) },
                    ) { Text("نسخ الكل") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("لا توجد أخطاء مسجلة.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "سيتم تسجيل نوع الخطأ وسببه ووقته تلقائيًا عند حدوث مشكلة.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onClear) { Text("مسح السجل") }
                    }
                }
                items(entries, key = { it.timestampEpochMs.toString() + it.type + it.reason }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(entry.type, style = MaterialTheme.typography.titleMedium)
                            Text("السبب: " + entry.reason)
                            Text(
                                "الوقت: " + Instant.ofEpochMilli(entry.timestampEpochMs),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            entry.detail?.let { Text("التفاصيل: $it") }
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            buildString {
                                                append("AHDownload Diagnostic Log\n")
                                                append("Time: ").append(Instant.ofEpochMilli(entry.timestampEpochMs)).append('\n')
                                                append("Type: ").append(entry.type).append('\n')
                                                append("Reason: ").append(entry.reason).append('\n')
                                                entry.detail?.let { append("Detail: ").append(it).append('\n') }
                                            },
                                        ),
                                    )
                                },
                            ) { Text("نسخ") }
                        }
                    }
                }
            }
        }
    }
}
