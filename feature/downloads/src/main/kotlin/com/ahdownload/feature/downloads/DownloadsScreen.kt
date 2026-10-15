package com.ahdownload.feature.downloads

import com.ahdownload.core.designsystem.DiagnosticButton
import com.ahdownload.core.designsystem.DiagnosticOutlinedButton
import com.ahdownload.core.designsystem.DiagnosticTextButton
import com.ahdownload.core.designsystem.DiagnosticIconButton
import com.ahdownload.core.designsystem.DiagnosticFilterChip
import com.ahdownload.core.designsystem.DiagnosticDropdownMenuItem

import android.content.ContentValues
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.ahdownload.core.designsystem.AHProgressBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHBottomNavDestination
import com.ahdownload.core.designsystem.AHBottomNavigationBar
import com.ahdownload.core.designsystem.AHCard
import com.ahdownload.core.designsystem.rememberUiTraceContext
import com.ahdownload.domain.download.DownloadProcessingMode
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.canBeCancelled
import com.ahdownload.domain.download.canBeResumed
import com.ahdownload.domain.download.canBeRemovedFromHistory
import com.ahdownload.domain.download.isActivelyRunning
import com.ahdownload.domain.favorites.FavoriteItem
import com.ahdownload.domain.favorites.FavoriteKey
import com.ahdownload.domain.favorites.FavoriteRepository
import com.ahdownload.domain.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private enum class DownloadMediaFilter(val label: String) {
    All("الكل"),
    Video("الفيديو"),
    Audio("الصوت"),
}

private enum class DownloadSectionType(val title: String) {
    Video("الفيديو"),
    Audio("الصوت"),
    Other("ملفات أخرى"),
}

private data class DownloadMediaSection(
    val type: DownloadSectionType,
    val records: List<DownloadRecord>,
)

private fun DownloadRecord.downloadSectionType(): DownloadSectionType = when {
    task.processingMode == DownloadProcessingMode.ExtractAudio ||
        task.mediaKind == MediaKind.Audio -> DownloadSectionType.Audio
    task.mediaKind == MediaKind.Video -> DownloadSectionType.Video
    else -> DownloadSectionType.Other
}

@Composable
fun DownloadsRoute(
    repository: DownloadRepository,
    favoriteRepository: FavoriteRepository,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (DownloadRecord) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
    onShareDownload: (DownloadRecord) -> Unit,
    onDeleteDownloadFile: (DownloadRecord) -> Boolean,
    onOpenDownloadFolder: (DownloadRecord) -> Unit,
    onOpenStudio: (DownloadRecord) -> Unit,
    uiTraceLogger: UiTraceLogger,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateYouTubeSearch: () -> Unit,
    onNavigateSettings: () -> Unit,
    activeDownloads: Int = 0,
    performanceSummaries: Map<String, DownloadPerformanceSummary> = emptyMap(),
) {
    val controls = remember(repository, onPauseDownload, onResumeDownload, onCancelDownload) {
        object : DownloadControls {
            override fun pause(taskId: String) = onPauseDownload(taskId)
            override fun resume(record: DownloadRecord) = onResumeDownload(record)
            override fun cancel(taskId: String) = onCancelDownload(taskId)
        }
    }
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory(repository, controls))
    val records by vm.records.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val favorites by favoriteRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteUrls = remember(favorites) { favorites.map { FavoriteKey.fromUrl(it.url) }.toSet() }
    val favoriteScope = rememberCoroutineScope()
    val transferStats = remember { mutableStateMapOf<String, TransferStats>() }
    val lastSamples = remember { mutableMapOf<String, TransferSample>() }
    var refreshTick by remember { mutableLongStateOf(0L) }
    var missingFiles by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000L)
            refreshTick++
        }
    }

    LaunchedEffect(records, refreshTick) {
        val snapshot = withContext(Dispatchers.IO) {
            records
                .filter { it.status == DownloadStatus.COMPLETED }
                .associate { it.task.id to destinationExists(context, it) }
        }
        missingFiles = snapshot.filterValues { !it }.keys
    }

    LaunchedEffect(records) {
        records.forEach { record ->
            if (record.status != DownloadStatus.DOWNLOADING) {
                transferStats.remove(record.task.id)
                lastSamples.remove(record.task.id)
                return@forEach
            }
            val current = TransferSample(record.bytesDownloaded, record.updatedAtEpochMs)
            val previous = lastSamples[record.task.id]
            if (previous != null) {
                val elapsedMs = current.epochMs - previous.epochMs
                val bytesDelta = current.bytes - previous.bytes
                when {
                    bytesDelta < 0L -> transferStats.remove(record.task.id)
                    elapsedMs > 0L && bytesDelta > 0L -> {
                        val measuredSpeed = (
                            bytesDelta.toDouble() * 1_000.0 / elapsedMs.toDouble()
                        ).coerceIn(1.0, Long.MAX_VALUE.toDouble()).toLong()
                        transferStats[record.task.id] = TransferStats(
                            bytesPerSecond = measuredSpeed,
                            totalBytes = record.totalBytes,
                            sampledAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
            lastSamples[record.task.id] = current
        }
    }

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(records) {
        val componentNames = buildList {
            add("topbar")
            add("search")
            add("filters")
            if (records.isEmpty()) add("empty_state") else add("download_cards")
            if (records.any { it.status.isActivelyRunning || it.status == DownloadStatus.PAUSED }) add("active_controls")
            if (records.any { it.status == DownloadStatus.FAILED }) add("retry_controls")
            if (records.any { it.status == DownloadStatus.COMPLETED }) add("completed_actions")
            add("bottom_navigation")
        }.joinToString(",")
        uiTraceLogger.snapshot(
            screen = "DOWNLOADS",
            component = "DownloadsScreen",
            components = componentNames,
            stateSummary = "records=" + records.size +
                ";active=" + records.count { it.status.isActivelyRunning } +
                ";paused=" + records.count { it.status == DownloadStatus.PAUSED } +
                ";completed=" + records.count { it.status == DownloadStatus.COMPLETED } +
                ";failed=" + records.count { it.status == DownloadStatus.FAILED },
            context = uiContext,
        )
    }

    DownloadsScreen(
        repository = repository,
        favorites = favoriteUrls,
        records = records,
        onBack = onBack,
        onNavigateHome = onNavigateHome,
        onNavigateYouTubeSearch = onNavigateYouTubeSearch,
        onNavigateSettings = onNavigateSettings,
        activeDownloads = activeDownloads,
        transferStats = transferStats,
        performanceSummaries = performanceSummaries,
        missingFileIds = missingFiles,
        onPause = vm::pause,
        onResume = vm::resume,
        onPauseAll = vm::pauseAll,
        onResumeAll = vm::resumeAll,
        onCancelAll = vm::cancelAll,
        onCancel = vm::cancel,
        onRetry = vm::retry,
        onDeleteHistory = vm::deleteHistory,
        onOpenDownload = onOpenDownload,
        onShareDownload = onShareDownload,
        onDeleteDownloadFile = onDeleteDownloadFile,
        onOpenDownloadFolder = onOpenDownloadFolder,
        onOpenStudio = onOpenStudio,
        onToggleFavorite = { record ->
            val url = (record.task.sourcePageUrl ?: record.task.sourceUrl).trim()
            val key = FavoriteKey.fromUrl(url)
            val existing = favorites.firstOrNull { FavoriteKey.fromUrl(it.url) == key }
            val item = FavoriteItem(
                id = key,
                url = url,
                title = record.task.displayName,
                thumbnailUrl = record.task.thumbnailUrl,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            favoriteScope.launch {
                favoriteRepository.setFavorite(item, favorite = existing == null)
            }
        },
        uiTraceLogger = uiTraceLogger,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(
    repository: DownloadRepository,
    records: List<DownloadRecord>,
    favorites: Set<String>,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateYouTubeSearch: () -> Unit,
    onNavigateSettings: () -> Unit,
    activeDownloads: Int,
    transferStats: Map<String, TransferStats>,
    performanceSummaries: Map<String, DownloadPerformanceSummary>,
    missingFileIds: Set<String>,
    onPause: (DownloadRecord) -> Unit,
    onResume: (DownloadRecord) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    onCancel: (DownloadRecord) -> Unit,
    onRetry: (DownloadRecord) -> Unit,
    onDeleteHistory: (DownloadRecord) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
    onShareDownload: (DownloadRecord) -> Unit,
    onDeleteDownloadFile: (DownloadRecord) -> Boolean,
    onOpenDownloadFolder: (DownloadRecord) -> Unit,
    onOpenStudio: (DownloadRecord) -> Unit,
    onToggleFavorite: (DownloadRecord) -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var mediaFilter by rememberSaveable { mutableStateOf(DownloadMediaFilter.All) }
    var statusFilter by rememberSaveable { mutableStateOf(DownloadStatusFilter.All) }
    var sortOption by rememberSaveable { mutableStateOf(DownloadSortOption.Recent) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingRename by remember { mutableStateOf<DownloadRecord?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var bulkMenuExpanded by remember { mutableStateOf(false) }
    val renameScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(feedback) {
        val message = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        feedback = null
    }

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filtered = remember(records, favoritesOnly, normalizedQuery, favorites, statusFilter, sortOption) {
        sortDownloadRecords(
            records = records.filter { record ->
                val matchesStatus = statusFilter.matches(record.status)
                val matchesFavorite = !favoritesOnly ||
                    FavoriteKey.fromUrl(record.task.sourcePageUrl ?: record.task.sourceUrl) in favorites
                val title = record.task.displayName.orEmpty()
                val path = record.task.destinationPath
                matchesStatus && matchesFavorite && (
                    normalizedQuery.isBlank() ||
                        title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                        path.lowercase(Locale.ROOT).contains(normalizedQuery)
                    )
            },
            option = sortOption,
        )
    }

    val visibleSections = remember(filtered, mediaFilter) {
        val sectionTypes = when (mediaFilter) {
            DownloadMediaFilter.All -> DownloadSectionType.entries
            DownloadMediaFilter.Video -> listOf(DownloadSectionType.Video)
            DownloadMediaFilter.Audio -> listOf(DownloadSectionType.Audio)
        }
        sectionTypes.mapNotNull { type ->
            filtered.filter { it.downloadSectionType() == type }
                .takeIf { it.isNotEmpty() }
                ?.let { DownloadMediaSection(type, it) }
        }
    }

    val videoCount = records.count { it.downloadSectionType() == DownloadSectionType.Video }
    val audioCount = records.count { it.downloadSectionType() == DownloadSectionType.Audio }
    val activeCount = records.count { it.status.isActivelyRunning }
    val pausedCount = records.count { it.status == DownloadStatus.PAUSED }
    val completedCount = records.count { it.status == DownloadStatus.COMPLETED }
    val statusCounts = remember(records) {
        DownloadStatusFilter.entries.associateWith { filter ->
            records.count { filter.matches(it.status) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("التنزيلات")
                        Text(
                            "تنظيم ومتابعة ملفاتك",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    DiagnosticIconButton(
                        trackingScreen = "DOWNLOADS",
                        trackingId = "DOWNLOADS.iconbutton.01",
                        trackingLabel = "DOWNLOADS.iconbutton.01",
                        disabledReason = "callsite_precondition_not_explicit",
                        onClick = {
                            uiTraceLogger.interaction("DOWNLOADS", "back_button", "back")
                            onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    DiagnosticIconButton(
                        trackingScreen = "DOWNLOADS",
                        trackingId = "DOWNLOADS.iconbutton.settings",
                        trackingLabel = "الإعدادات",
                        disabledReason = "navigation_is_available",
                        onClick = {
                            uiTraceLogger.interaction("DOWNLOADS", "settings_button", "open_settings")
                            onNavigateSettings()
                        },
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "الإعدادات")
                    }
                    if (activeCount > 0 || records.any { it.status.canBeCancelled || it.status.canBeResumed }) {
                        Box {
                            DiagnosticIconButton(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.iconbutton.02",
                                trackingLabel = "DOWNLOADS.iconbutton.02",
                                disabledReason = "callsite_precondition_not_explicit",onClick = { bulkMenuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "إدارة التنزيلات")
                            }
                            DropdownMenu(
                                expanded = bulkMenuExpanded,
                                onDismissRequest = { bulkMenuExpanded = false },
                            ) {
                                if (activeCount > 0) {
                                    DiagnosticDropdownMenuItem(
                                        trackingScreen = "DOWNLOADS",
                                        trackingId = "DOWNLOADS.dropdownmenuitem.01",
                                        trackingLabel = "إيقاف الكل",
                                        disabledReason = "callsite_precondition_not_explicit",
                                        text = { Text("إيقاف الكل") },
                                        onClick = {
                                            bulkMenuExpanded = false
                                            onPauseAll()
                                        },
                                    )
                                }
                                if (records.any { it.status.canBeCancelled }) {
                                    DiagnosticDropdownMenuItem(
                                        trackingScreen = "DOWNLOADS",
                                        trackingId = "DOWNLOADS.dropdownmenuitem.02",
                                        trackingLabel = "إلغاء الكل",
                                        disabledReason = "callsite_precondition_not_explicit",
                                        text = { Text("إلغاء الكل") },
                                        onClick = {
                                            bulkMenuExpanded = false
                                            onCancelAll()
                                        },
                                    )
                                }
                                if (records.any { it.status.canBeResumed }) {
                                    DiagnosticDropdownMenuItem(
                                        trackingScreen = "DOWNLOADS",
                                        trackingId = "DOWNLOADS.dropdownmenuitem.03",
                                        trackingLabel = "استئناف الكل",
                                        disabledReason = "callsite_precondition_not_explicit",
                                        text = { Text("استئناف الكل") },
                                        onClick = {
                                            bulkMenuExpanded = false
                                            onResumeAll()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            AHBottomNavigationBar(
                trackingScreen = "DOWNLOADS",
                selected = AHBottomNavDestination.DOWNLOADS,
                activeDownloads = activeDownloads,
                onDestinationSelected = { destination ->
                    when (destination) {
                        AHBottomNavDestination.HOME -> {
                            uiTraceLogger.interaction("DOWNLOADS", "bottom_nav_home", "open_home")
                            onNavigateHome()
                        }
                        AHBottomNavDestination.YOUTUBE_SEARCH -> {
                            uiTraceLogger.interaction("DOWNLOADS", "bottom_nav_youtube_search", "open_youtube_search")
                            onNavigateYouTubeSearch()
                        }
                        AHBottomNavDestination.DOWNLOADS -> Unit
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            EmptyDownloads(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onGoHome = onNavigateHome,
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "download_summary") {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        activeCount.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        "نشطة",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        pausedCount.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "متوقفة",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        completedCount.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        "مكتملة",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                DiagnosticIconButton(
                                    trackingScreen = "DOWNLOADS",
                                    trackingId = "DOWNLOADS.iconbutton.03",
                                    trackingLabel = "DOWNLOADS.iconbutton.03",
                                    disabledReason = "callsite_precondition_not_explicit",onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "مسح البحث")
                                }
                            }
                        },
                        placeholder = { Text("ابحث في سجل التنزيلات") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }

                item(key = "download-status-filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "الحالة",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DownloadStatusFilter.entries, key = { it.name }) { filter ->
                                val icon = when (filter) {
                                    DownloadStatusFilter.All -> Icons.Rounded.Download
                                    DownloadStatusFilter.Active -> Icons.Rounded.PlayArrow
                                    DownloadStatusFilter.Paused -> Icons.Rounded.Pause
                                    DownloadStatusFilter.Completed -> Icons.Rounded.CheckCircle
                                    DownloadStatusFilter.Failed -> Icons.Rounded.ErrorOutline
                                    DownloadStatusFilter.Cancelled -> Icons.Rounded.Cancel
                                }
                                DiagnosticFilterChip(
                                    trackingScreen = "DOWNLOADS",
                                    trackingId = "DOWNLOADS.status-filter.${filter.name.lowercase(Locale.ROOT)}",
                                    trackingLabel = filter.label,
                                    disabledReason = "none",
                                    selected = filter == statusFilter,
                                    onClick = { statusFilter = filter },
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("${filter.label} · ${statusCounts[filter] ?: 0}")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DiagnosticFilterChip(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.filterchip.favorites",
                            trackingLabel = "المفضلة",
                            disabledReason = "none",
                            selected = favoritesOnly,
                            onClick = { favoritesOnly = !favoritesOnly },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text("المفضلة · ${records.count { FavoriteKey.fromUrl(it.task.sourcePageUrl ?: it.task.sourceUrl) in favorites }}")
                                }
                            },
                        )
                    }
                }

                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DownloadMediaFilter.entries.forEach { item ->
                            val count = when (item) {
                                DownloadMediaFilter.All -> records.size
                                DownloadMediaFilter.Video -> videoCount
                                DownloadMediaFilter.Audio -> audioCount
                            }
                            item {
                                DiagnosticFilterChip(
                                    trackingScreen = "DOWNLOADS",
                                    trackingId = "DOWNLOADS.media-filter.${item.name.lowercase(Locale.ROOT)}",
                                    trackingLabel = item.label,
                                    disabledReason = "none",
                                    selected = item == mediaFilter,
                                    onClick = { mediaFilter = item },
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            when (item) {
                                                DownloadMediaFilter.All -> Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                DownloadMediaFilter.Video -> Icon(Icons.Rounded.VideoFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                                DownloadMediaFilter.Audio -> Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                            Text("${item.label} · $count")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }


                item(key = "download-sort-options") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "ترتيب السجل",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            DiagnosticOutlinedButton(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.sort.open",
                                trackingLabel = "تغيير ترتيب السجل",
                                disabledReason = "none",
                                onClick = { sortMenuExpanded = true },
                            ) {
                                Text(sortOption.label)
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                DownloadSortOption.entries.forEach { option ->
                                    DiagnosticDropdownMenuItem(
                                        trackingScreen = "DOWNLOADS",
                                        trackingId = "DOWNLOADS.sort." + option.name.lowercase(Locale.ROOT),
                                        trackingLabel = option.label,
                                        disabledReason = "none",
                                        text = { Text(option.label) },
                                        onClick = {
                                            sortOption = option
                                            sortMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (visibleSections.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(38.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text("لا توجد نتائج مطابقة", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "غيّر البحث أو الفلتر لعرض عناصر أخرى.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    visibleSections.forEach { section ->
                        item(key = "download-section-${section.type.name}") {
                            val sectionIcon = when (section.type) {
                                DownloadSectionType.Video -> Icons.Rounded.VideoFile
                                DownloadSectionType.Audio -> Icons.Rounded.AudioFile
                                DownloadSectionType.Other -> Icons.Rounded.Image
                            }
                            val sectionContainer = when (section.type) {
                                DownloadSectionType.Video -> MaterialTheme.colorScheme.primaryContainer
                                DownloadSectionType.Audio -> MaterialTheme.colorScheme.secondaryContainer
                                DownloadSectionType.Other -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            val sectionContent = when (section.type) {
                                DownloadSectionType.Video -> MaterialTheme.colorScheme.onPrimaryContainer
                                DownloadSectionType.Audio -> MaterialTheme.colorScheme.onSecondaryContainer
                                DownloadSectionType.Other -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = sectionContainer,
                                    contentColor = sectionContent,
                                ) {
                                    Icon(
                                        sectionIcon,
                                        contentDescription = null,
                                        modifier = Modifier.padding(9.dp).size(19.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(section.type.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (section.records.size == 1) "عنصر واحد" else "${section.records.size} عناصر",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(section.records, key = { it.task.id }) { record ->
                            DownloadRecordCard(
                                record = record,
                                fileAvailable = record.status != DownloadStatus.COMPLETED || record.task.id !in missingFileIds,
                                transferStats = transferStats[record.task.id],
                                performanceSummary = performanceSummaries[record.task.id],
                                onPause = {
                                    uiTraceLogger.interaction("DOWNLOADS", "pause_control", "pause")
                                    onPause(record)
                                },
                                onResume = {
                                    uiTraceLogger.interaction("DOWNLOADS", "resume_control", "resume")
                                    onResume(record)
                                },
                                onCancel = {
                                    uiTraceLogger.interaction("DOWNLOADS", "cancel_control", "cancel")
                                    onCancel(record)
                                },
                                onRetry = {
                                    uiTraceLogger.interaction("DOWNLOADS", "retry_control", "retry")
                                    onRetry(record)
                                },
                                onDeleteHistory = {
                                    pendingDelete = record
                                },
                                onRenameDownload = {
                                    renameValue = record.task.displayName.orEmpty().substringBeforeLast('.', record.task.displayName.orEmpty())
                                    pendingRename = record
                                },
                                onOpenDownload = {
                                    uiTraceLogger.interaction("DOWNLOADS", "open_control", "open")
                                    onOpenDownload(record)
                                },
                                onShareDownload = {
                                    uiTraceLogger.interaction("DOWNLOADS", "share_control", "share")
                                    onShareDownload(record)
                                },
                                onDeleteDownloadFile = {
                                    val deleted = onDeleteDownloadFile(record)
                                    feedback = if (deleted) "تم حذف الملف." else "تعذر حذف الملف."
                                },
                                onOpenDownloadFolder = {
                                    onOpenDownloadFolder(record)
                                },
                                onOpenStudio = {
                                    uiTraceLogger.interaction("DOWNLOADS", "studio_control", "open_studio")
                                    onOpenStudio(record)
                                },
                                favorite = FavoriteKey.fromUrl(record.task.sourcePageUrl ?: record.task.sourceUrl) in favorites,
                                onToggleFavorite = {
                                    uiTraceLogger.interaction("DOWNLOADS", "favorite_control", "toggle")
                                    onToggleFavorite(record)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("إزالة من السجل؟") },
            text = {
                Text(
                    "سيُزال هذا العنصر من سجل AHDownload. لن يُحذف الملف المكتمل من الجهاز.",
                )
            },
            confirmButton = {
                DiagnosticButton(
                    trackingScreen = "DOWNLOADS",
                    trackingId = "DOWNLOADS.button.01",
                    trackingLabel = "إزالة",
                    disabledReason = "callsite_precondition_not_explicit",
                    onClick = {
                        onDeleteHistory(record)
                        pendingDelete = null
                        feedback = "تمت إزالة العنصر من السجل."
                    },
                ) {
                    Text("إزالة")
                }
            },
            dismissButton = {
                DiagnosticTextButton(
                    trackingScreen = "DOWNLOADS",
                    trackingId = "DOWNLOADS.textbutton.01",
                    trackingLabel = "إلغاء",
                    disabledReason = "callsite_precondition_not_explicit",onClick = { pendingDelete = null }) {
                    Text("إلغاء")
                }
            },
        )
    }

    pendingRename?.let { record ->
        AlertDialog(
            onDismissRequest = { if (!renameBusy) pendingRename = null },
            title = { Text("إعادة تسمية الملف") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("اسم الملف") },
                )
            },
            confirmButton = {
                DiagnosticButton(
                    trackingScreen = "DOWNLOADS",
                    trackingId = "DOWNLOADS.button.02",
                    trackingLabel = "حفظ اسم الملف",
                    disabledReason = "اسم الملف فارغ أو عملية الحفظ جارية",
                    enabled = renameValue.trim().isNotBlank() && !renameBusy,
                    onClick = {
                        renameBusy = true
                        renameScope.launch {
                            val renamed = renameDownloadRecord(
                                repository = repository,
                                context = context,
                                record = record,
                                requestedName = renameValue,
                            )
                            renameBusy = false
                            pendingRename = null
                            feedback = if (renamed) "تمت إعادة تسمية الملف." else "تعذر إعادة تسمية الملف."
                        }
                    },
                ) {
                    Text(if (renameBusy) "جارٍ الحفظ..." else "حفظ")
                }
            },
            dismissButton = {
                DiagnosticTextButton(
                    trackingScreen = "DOWNLOADS",
                    trackingId = "DOWNLOADS.textbutton.02",
                    trackingLabel = "إلغاء",
                    disabledReason = "عملية إعادة التسمية جارية",
                    enabled = !renameBusy,
                    onClick = { pendingRename = null },
                ) {
                    Text("إلغاء")
                }
            },
        )
    }
}

@Composable
private fun EmptyDownloads(
    modifier: Modifier,
    onGoHome: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("لا توجد تنزيلات بعد", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "عندما تبدأ تنزيلًا سيظهر هنا مع حالته وتقدمه وإجراءات التحكم.",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        DiagnosticButton(
            trackingScreen = "DOWNLOADS",
            trackingId = "DOWNLOADS.button.03",
            trackingLabel = "بدء تنزيل",
            disabledReason = "callsite_precondition_not_explicit",onClick = onGoHome) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("بدء تنزيل")
        }
    }
}

@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    fileAvailable: Boolean = true,
    transferStats: TransferStats? = null,
    performanceSummary: DownloadPerformanceSummary? = null,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDeleteHistory: () -> Unit,
    onRenameDownload: () -> Unit,
    onOpenDownload: () -> Unit,
    onShareDownload: () -> Unit,
    onDeleteDownloadFile: () -> Unit,
    onOpenDownloadFolder: () -> Unit,
    onOpenStudio: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val rawProgress = record.totalBytes
        ?.takeIf { it > 0L }
        ?.let { (record.bytesDownloaded.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
    // Reserve 100% for a completed record whose output file was actually verified.
    val progress = if (record.status == DownloadStatus.COMPLETED && fileAvailable) {
        1f
    } else {
        rawProgress?.coerceAtMost(0.99f)
    }
    var previousStatus by remember(record.task.id) { mutableStateOf(record.status) }
    var showSuccessAnimation by remember(record.task.id) { mutableStateOf(false) }

    LaunchedEffect(record.status) {
        if (
            record.status == DownloadStatus.COMPLETED &&
            previousStatus != DownloadStatus.COMPLETED &&
            fileAvailable
        ) {
            showSuccessAnimation = true
        }
        previousStatus = record.status
    }

    LaunchedEffect(showSuccessAnimation) {
        if (showSuccessAnimation) {
            delay(2_000)
            showSuccessAnimation = false
        }
    }
    val title = record.task.displayName?.takeIf { it.isNotBlank() }
        ?: record.task.destinationPath.substringAfterLast(File.separatorChar)

    val statusText = when {
        record.status == DownloadStatus.COMPLETED && !fileAvailable -> "الملف غير موجود"
        else -> statusLabel(record.status)
    }
    val statusIcon = when (record.status) {
        DownloadStatus.COMPLETED -> if (fileAvailable) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline
        DownloadStatus.FAILED -> Icons.Rounded.Refresh
        DownloadStatus.PAUSED -> Icons.Rounded.Pause
        DownloadStatus.CANCELLED -> Icons.Rounded.Cancel
        else -> Icons.Rounded.Download
    }

    AHCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                MediaThumbnail(
                    url = record.task.thumbnailUrl,
                    kind = record.task.mediaKind,
                    modifier = Modifier
                        .size(width = 94.dp, height = 68.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentDescription = "صورة مصغرة: " + title,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = statusColor(record.status),
                        )
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor(record.status),
                        )
                    }
                    val kindText = record.task.mediaKind?.let(::kindLabel)
                    val relativeTime = DateUtils.getRelativeTimeSpanString(
                        record.updatedAtEpochMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                    Text(
                        listOfNotNull(
                            kindText?.let { "$it · ${extensionLabel(title)}" },
                            relativeTime,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    AnimatedVisibility(
                        visible = showSuccessAnimation && fileAvailable,
                        enter = fadeIn(animationSpec = tween(220)) +
                            scaleIn(initialScale = 0.94f, animationSpec = tween(220)),
                        exit = fadeOut(animationSpec = tween(160)),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    "اكتمل بنجاح",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                DiagnosticIconButton(
                    trackingScreen = "DOWNLOADS",
                    trackingId = "DOWNLOADS.iconbutton.04",
                    trackingLabel = "DOWNLOADS.iconbutton.04",
                    disabledReason = "callsite_precondition_not_explicit",
                    onClick = onToggleFavorite,
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (favorite) "إزالة من المفضلة" else "إضافة إلى المفضلة"
                    },
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    DiagnosticIconButton(
                        trackingScreen = "DOWNLOADS",
                        trackingId = "DOWNLOADS.iconbutton.05",
                        trackingLabel = "DOWNLOADS.iconbutton.05",
                        disabledReason = "callsite_precondition_not_explicit",onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "المزيد")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (record.status == DownloadStatus.COMPLETED && fileAvailable) {
                            DiagnosticDropdownMenuItem(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.dropdownmenuitem.04",
                                trackingLabel = "Smart Studio",
                                disabledReason = "callsite_precondition_not_explicit",
                                text = { Text("Smart Studio") },
                                leadingIcon = { Icon(Icons.Rounded.VideoFile, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenStudio()
                                },
                            )
                        }
                        if (record.status == DownloadStatus.COMPLETED && fileAvailable && record.destinationUri != null) {
                            DiagnosticDropdownMenuItem(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.dropdownmenuitem.07",
                                trackingLabel = "فتح مجلد الحفظ",
                                disabledReason = "callsite_precondition_not_explicit",
                                text = { Text("فتح مجلد الحفظ") },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenDownloadFolder()
                                },
                            )
                            DiagnosticDropdownMenuItem(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.dropdownmenuitem.08",
                                trackingLabel = "إعادة التسمية",
                                disabledReason = "callsite_precondition_not_explicit",
                                text = { Text("إعادة التسمية") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRenameDownload()
                                },
                            )
                            DiagnosticDropdownMenuItem(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.dropdownmenuitem.09",
                                trackingLabel = "حذف الملف",
                                disabledReason = "callsite_precondition_not_explicit",
                                text = { Text("حذف الملف") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteDownloadFile()
                                },
                            )
                        }
                        if (record.status == DownloadStatus.COMPLETED && !fileAvailable) {
                            DiagnosticDropdownMenuItem(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.dropdownmenuitem.10",
                                trackingLabel = "إعادة التنزيل",
                                disabledReason = "callsite_precondition_not_explicit",
                                text = { Text("إعادة التنزيل") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRetry()
                                },
                            )
                        }
                        if (record.status.canBeRemovedFromHistory) {
                            DiagnosticDropdownMenuItem(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.dropdownmenuitem.11",
                                trackingLabel = "إزالة من السجل",
                                disabledReason = "callsite_precondition_not_explicit",
                                text = { Text("إزالة من السجل") },
                                leadingIcon = { Icon(Icons.Rounded.Clear, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteHistory()
                                },
                            )
                        }
                    }
                }
            }

            if (record.status == DownloadStatus.DOWNLOADING) {
                val nowEpochMs = System.currentTimeMillis()
                val speed = visibleTransferSpeed(
                    bytesPerSecond = transferStats?.bytesPerSecond,
                    sampledAtEpochMs = transferStats?.sampledAtEpochMs,
                    nowEpochMs = nowEpochMs,
                )
                val remainingSeconds = estimatedDownloadRemainingSeconds(
                    totalBytes = record.totalBytes,
                    downloadedBytes = record.bytesDownloaded,
                    bytesPerSecond = speed,
                )
                val byteSummary = record.totalBytes?.takeIf { it > 0L }?.let {
                    "${formatBytes(record.bytesDownloaded)} / ${formatBytes(it)}"
                } ?: "${formatBytes(record.bytesDownloaded)} · الحجم الإجمالي غير معروف"

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            byteSummary,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                rawProgress != null && rawProgress >= 1f -> "جارٍ إنهاء الملف"
                                progress != null -> "${(progress * 100).toInt()}%"
                                else -> "جارٍ التنزيل"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DownloadProgressTrack(
                        progress = progress,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (speed != null || remainingSeconds != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            speed?.let {
                                Text(
                                    "السرعة ${formatBytes(it)}/ث",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            remainingSeconds?.let {
                                Text(
                                    "متبقٍ تقريبًا ${formatDurationSeconds(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (record.status == DownloadStatus.QUEUED ||
                record.status == DownloadStatus.PREPARING
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (record.status == DownloadStatus.QUEUED) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {}
                    } else {
                        DownloadProgressTrack(
                            progress = null,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (record.status == DownloadStatus.QUEUED) {
                            "بانتظار بدء التنزيل"
                        } else {
                            "جارٍ تجهيز مصدر التنزيل"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (record.status == DownloadStatus.PAUSED) {
                val pausedProgress = progress
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            record.totalBytes?.takeIf { it > 0L }?.let {
                                "${formatBytes(record.bytesDownloaded)} / ${formatBytes(it)}"
                            } ?: "${formatBytes(record.bytesDownloaded)} · الحجم الإجمالي غير معروف",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            pausedProgress?.let { "${(it * 100).toInt()}%" } ?: "متوقف مؤقتًا",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pausedProgress != null) {
                        DownloadProgressTrack(
                            progress = pausedProgress,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // A paused item with unknown total size must not show a moving indeterminate bar.
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {}
                    }
                    Text(
                        "متوقف مؤقتًا · اضغط «استئناف» للمتابعة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (record.status == DownloadStatus.COMPLETED && fileAvailable) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            record.totalBytes?.takeIf { it > 0L }?.let {
                                "${formatBytes(record.bytesDownloaded)} / ${formatBytes(it)}"
                            } ?: formatBytes(record.bytesDownloaded),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "100% · اكتمل",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    DownloadProgressTrack(
                        progress = 1f,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (record.status == DownloadStatus.FAILED && record.bytesDownloaded > 0L) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "توقف عند ${formatBytes(record.bytesDownloaded)}" +
                            (record.totalBytes?.takeIf { it > 0L }?.let { " / ${formatBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (progress != null) {
                        DownloadProgressTrack(
                            progress = progress,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (record.bytesDownloaded > 0L) {
                Text(
                    formatBytes(record.bytesDownloaded) +
                        (record.totalBytes?.let { " / " + formatBytes(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (record.status.canBeRemovedFromHistory && performanceSummary != null &&
                (performanceSummary.transferredBytes > 0L || performanceSummary.durationMs > 0L)
            ) {
                Text(
                    "متوسط السرعة: " + formatBytes(performanceSummary.averageBytesPerSecond) +
                        "/s · الذروة: " + formatBytes(performanceSummary.peakBytesPerSecond) +
                        "/s · زمن النقل: " + formatPerformanceDuration(performanceSummary.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (record.status == DownloadStatus.FAILED) {
                Text(
                    failureSummary(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                destinationLabel(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (record.status) {
                    DownloadStatus.QUEUED,
                    DownloadStatus.PREPARING,
                    DownloadStatus.DOWNLOADING -> {
                        DiagnosticOutlinedButton(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.outlinedbutton.01",
                            trackingLabel = "إيقاف مؤقت",
                            disabledReason = "callsite_precondition_not_explicit",
                            modifier = Modifier.weight(1f),
                            onClick = onPause,
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إيقاف مؤقت")
                        }
                        DiagnosticOutlinedButton(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.outlinedbutton.02",
                            trackingLabel = "إلغاء",
                            disabledReason = "callsite_precondition_not_explicit",
                            modifier = Modifier.weight(1f),
                            onClick = onCancel,
                        ) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        DiagnosticButton(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.button.04",
                            trackingLabel = "استئناف",
                            disabledReason = "callsite_precondition_not_explicit",
                            modifier = Modifier.weight(1f),
                            onClick = onResume,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("استئناف")
                        }
                        DiagnosticOutlinedButton(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.outlinedbutton.cancel_paused",
                            trackingLabel = "إلغاء نهائي",
                            disabledReason = "callsite_precondition_not_explicit",
                            modifier = Modifier.weight(1f),
                            onClick = onCancel,
                        ) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إلغاء")
                        }
                    }
                    DownloadStatus.CANCELLED -> {
                        DiagnosticButton(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.button.05",
                            trackingLabel = "إعادة التنزيل",
                            disabledReason = "callsite_precondition_not_explicit",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onResume,
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إعادة التنزيل")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        DiagnosticButton(
                            trackingScreen = "DOWNLOADS",
                            trackingId = "DOWNLOADS.button.06",
                            trackingLabel = "إعادة المحاولة",
                            disabledReason = "callsite_precondition_not_explicit",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onRetry,
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text("إعادة المحاولة")
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        if (!fileAvailable) {
                            DiagnosticButton(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.button.07",
                                trackingLabel = "إعادة التنزيل",
                                disabledReason = "callsite_precondition_not_explicit",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onRetry,
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("إعادة التنزيل")
                            }
                        } else if (record.destinationUri != null) {
                            DiagnosticButton(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.button.08",
                                trackingLabel = "فتح",
                                disabledReason = "callsite_precondition_not_explicit",
                                modifier = Modifier.weight(1f),
                                onClick = onOpenDownload,
                            ) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("فتح")
                            }
                            DiagnosticOutlinedButton(
                                trackingScreen = "DOWNLOADS",
                                trackingId = "DOWNLOADS.outlinedbutton.03",
                                trackingLabel = "مشاركة",
                                disabledReason = "callsite_precondition_not_explicit",
                                modifier = Modifier.weight(1f),
                                onClick = onShareDownload,
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("مشاركة")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    url: String?,
    kind: MediaKind?,
    modifier: Modifier,
    contentDescription: String,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (url.isNullOrBlank()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    when (kind) {
                        MediaKind.Video -> Icons.Rounded.VideoFile
                        MediaKind.Audio -> Icons.Rounded.AudioFile
                        MediaKind.Image -> Icons.Rounded.Image
                        else -> Icons.Rounded.FolderOpen
                    },
                    contentDescription = contentDescription,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun renameDownloadRecord(
    repository: DownloadRepository,
    context: android.content.Context,
    record: DownloadRecord,
    requestedName: String,
): Boolean {
    val source = record.destinationUri?.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
    val extension = record.task.destinationPath
        .substringAfterLast('.', "")
        .takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        .orEmpty()
    val safeBase = requestedName
        .replace(Regex("[\\/:*?\"<>|\\r\\n]+"), " ")
        .trim()
        .trimEnd('.')
        .take(120)
    if (safeBase.isBlank()) return false
    val finalName = if (extension.isNotBlank() && !safeBase.endsWith(extension, ignoreCase = true)) {
        safeBase + extension
    } else {
        safeBase
    }

    val renamed = runCatching {
        when {
            source?.scheme == "content" -> {
                val displayUpdated = runCatching {
                    context.contentResolver.update(
                        source,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, finalName) },
                        null,
                        null,
                    ) > 0
                }.getOrDefault(false)
                displayUpdated || runCatching {
                    DocumentsContract.renameDocument(context.contentResolver, source, finalName) != null
                }.getOrDefault(false)
            }
            else -> {
                val oldFile = File(record.task.destinationPath)
                val newFile = File(oldFile.parentFile, finalName)
                oldFile.exists() && (!newFile.exists()) && oldFile.renameTo(newFile)
            }
        }
    }.getOrDefault(false)

    if (!renamed) return false
    repository.upsert(
        record.copy(
            task = record.task.copy(
                displayName = finalName,
                destinationPath = File(record.task.destinationPath).parent?.let { File(it, finalName).absolutePath } ?: record.task.destinationPath,
            ),
            updatedAtEpochMs = System.currentTimeMillis(),
        ),
    )
    return true
}
private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "في قائمة الانتظار"
    DownloadStatus.PREPARING -> "جاري التجهيز"
    DownloadStatus.DOWNLOADING -> "جاري التنزيل"
    DownloadStatus.PAUSED -> "متوقف مؤقتًا"
    DownloadStatus.COMPLETED -> "اكتمل التنزيل"
    DownloadStatus.FAILED -> "فشل التنزيل"
    DownloadStatus.CANCELLED -> "تم إلغاء التنزيل"
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun destinationLabel(record: DownloadRecord): String = when {
    record.destinationUri?.startsWith("content://") == true -> "محفوظ في مجلد الجهاز"
    record.destinationUri != null -> "محفوظ على الجهاز"
    else -> "مسار داخلي مؤقت"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index.coerceAtLeast(0)])
}

private fun extensionLabel(name: String): String =
    name.substringAfterLast('.', "").uppercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "FILE"

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Video -> "فيديو"
    MediaKind.Audio -> "صوت"
    MediaKind.Image -> "صورة"
    MediaKind.Unknown -> "ملف"
}

@Composable
private fun DownloadProgressTrack(
    progress: Float?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    AHProgressBar(
        progress = progress,
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    )
}

private data class TransferSample(
    val bytes: Long,
    val epochMs: Long,
)

private data class TransferStats(
    val bytesPerSecond: Long,
    val totalBytes: Long?,
    val sampledAtEpochMs: Long,
)

private fun formatPerformanceDuration(durationMs: Long): String = when {
    durationMs < 1_000L -> "${durationMs} ms"
    durationMs < 60_000L -> String.format(Locale.US, "%.2f s", durationMs / 1_000.0)
    else -> formatDurationSeconds(durationMs / 1_000L)
}

private fun formatDurationSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) {
        "%02d:%02d:%02d".format(hours, minutes, remainder)
    } else {
        "%02d:%02d".format(minutes, remainder)
    }
}

private fun destinationExists(context: android.content.Context, record: DownloadRecord): Boolean {
    val raw = record.destinationUri?.takeIf { it.isNotBlank() }
    if (raw != null) {
        return runCatching {
            val uri = android.net.Uri.parse(raw)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
        }.getOrDefault(false)
    }
    return File(record.task.destinationPath).exists()
}
