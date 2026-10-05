package com.ahdownload.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.core.designsystem.AHGradientPrimaryButton
import com.ahdownload.core.designsystem.AHStatusPill
import com.ahdownload.domain.model.MediaLink

@Composable
fun HomeRoute(
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onUrlChanged = viewModel::onUrlChanged,
        onAnalyze = viewModel::analyze,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: HomeUiState,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AHDownload") },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "مركز التحميل الذكي",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "ألصق رابط الفيديو أو الوسائط وسنحدد المسار المناسب قبل بدء التنزيل.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                },
                label = { Text("رابط المحتوى") },
                placeholder = { Text("https://...") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text("التحليل الحالي محلي فقط؛ الاستخراج الفعلي سيأتي في طبقة Resolver.")
                },
            )

            Spacer(Modifier.height(14.dp))

            AHGradientPrimaryButton(
                text = if (state.analyzing) "جارٍ التحليل..." else "تحليل الرابط",
                enabled = state.url.isNotBlank() && !state.analyzing,
                onClick = onAnalyze,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            if (state.analyzing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                AHStatusPill(text = it)
            }

            state.result?.let { result ->
                Spacer(Modifier.height(8.dp))
                AnalysisCard(result)
            }
        }
    }
}

@Composable
private fun AnalysisCard(result: MediaLink) {
    val platform = result.platform.name
    val kind = result.kind.name
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("تم التعرف على الرابط", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AHStatusPill(platform)
            AHStatusPill(kind)
        }
        Text(
            text = "الخطوة التالية: Resolver سيستخرج الوسائط والجودات المتاحة قبل إنشاء مهمة التنزيل.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
