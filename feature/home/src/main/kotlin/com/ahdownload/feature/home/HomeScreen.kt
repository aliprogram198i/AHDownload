package com.ahdownload.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate

@Composable
fun HomeRoute(
    onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean,
    logger: DiagnosticLogger,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(onDownloadRequested, logger, context) {
        HomeViewModel.Factory(onDownloadRequested, logger, context)
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        state = state,
        onUrlChanged = viewModel::onUrlChanged,
        onAnalyze = viewModel::analyze,
        onSelectCandidate = viewModel::selectCandidate,
        onDownload = viewModel::downloadSelected,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: HomeUiState,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onDownload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AHDownload") },
                navigationIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                actions = { IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Rounded.ErrorOutline, contentDescription = "سجل الأخطاء") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("مركز التحميل الذكي", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "ألصق الرابط، استخرج الوسائط الحقيقية، اختر الجودة ثم أضفها إلى قائمة التنزيل.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    label = { Text("رابط المحتوى") },
                    placeholder = { Text("https://...") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                AHGradientPrimaryButton(
                    text = when {
                        state.analyzing -> "جارٍ تحليل الرابط..."
                        state.resolving -> "جارٍ استخراج الوسائط..."
                        else -> "تحليل واستخراج الوسائط"
                    },
                    enabled = state.url.isNotBlank() && !state.analyzing && !state.resolving,
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.analyzing || state.resolving) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.result?.let { link ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AHStatusPill(link.platform.name)
                        AHStatusPill(link.kind.name)
                    }
                }
            }
            state.resolution?.let { resolution ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(resolution.title ?: "وسائط متاحة", style = MaterialTheme.typography.titleLarge)
                        resolution.durationMs?.let {
                            Text((it / 1000).toString() + "s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(resolution.candidates, key = { it.id }) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        selected = candidate.id == state.selectedCandidateId,
                        validating = candidate.id == state.validatingCandidateId,
                        onSelect = { onSelectCandidate(candidate.id) },
                        onDownload = onDownload,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
                }
            }
            state.error?.let { error ->
                item { AHStatusPill(error) }
            }
            if (state.downloadQueued) {
                item { AHStatusPill("تمت إضافة التنزيل إلى قائمة الانتظار.") }
            }
            item {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "المصدر يُتحقق منه قبل الإدخال إلى Queue، والتقدم الفعلي يديره WorkManager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: MediaCandidate,
    selected: Boolean,
    validating: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val format = candidate.format
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !validating, onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AHStatusPill(if (format.kind == MediaKind.Audio) "صوت" else "فيديو")
                format.height?.let { AHStatusPill(it.toString() + "p") }
                format.bitrateKbps?.let { AHStatusPill(it.toString() + "kbps") }
                if (selected) AHStatusPill("محدد")
            }
            Text(
                when {
                    format.kind == MediaKind.Video && format.height != null -> "فيديو " + format.height + "p"
                    format.kind == MediaKind.Audio && format.bitrateKbps != null -> "صوت " + format.bitrateKbps + "kbps"
                    else -> "وسائط " + format.container
                },
                style = MaterialTheme.typography.titleMedium,
            )
            format.fileSizeBytes?.let {
                Text((it / 1024 / 1024).toString() + " MB")
            }
            Button(enabled = selected && !validating, onClick = onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (validating) "جارٍ التحقق..." else "تنزيل")
            }
        }
    }
}
