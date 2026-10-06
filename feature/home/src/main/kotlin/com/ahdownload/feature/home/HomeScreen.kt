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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    initialUrl: String? = null,
    onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean,
    logger: DiagnosticLogger,
    onOpenDiagnostics: () -> Unit,
    onOpenYouTubeSession: () -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(onDownloadRequested, logger, context) {
        HomeViewModel.Factory(onDownloadRequested, logger, context)
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialUrl) {
        initialUrl?.takeIf { it.isNotBlank() }?.let(viewModel::onUrlChanged)
    }

    HomeScreen(
        state = state,
        onUrlChanged = viewModel::onUrlChanged,
        onAnalyze = viewModel::analyze,
        onSelectCandidate = viewModel::selectCandidate,
        onDownload = viewModel::downloadSelected,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenYouTubeSession = onOpenYouTubeSession,
    )
}
