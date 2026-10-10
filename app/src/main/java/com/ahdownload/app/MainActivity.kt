package com.ahdownload.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateListOf
import com.ahdownload.app.diagnostics.DiagnosticsRoute
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.app.diagnostics.PersistentUiTraceLogger
import com.ahdownload.app.download.DownloadLauncher
import com.ahdownload.app.download.MediaAudioExtractor
import com.ahdownload.app.favorites.FavoritesStore
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.app.settings.DownloadPreferencesStore
import com.ahdownload.app.settings.SettingsRoute
import com.ahdownload.app.settings.ThemePreferenceStore
import com.ahdownload.app.performance.PerformanceLogRoute
import com.ahdownload.app.performance.PerformanceLogStore
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.designsystem.AHTheme
import com.ahdownload.core.designsystem.LocalDiagnosticUiTraceLogger
import androidx.compose.runtime.CompositionLocalProvider
import com.ahdownload.core.designsystem.AHThemeMode
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.feature.downloads.DownloadsRoute
import com.ahdownload.feature.home.HomeRoute
import com.ahdownload.feature.studio.StudioRoute
import com.ahdownload.feature.welcome.WelcomeRoute
import java.io.File
import kotlinx.coroutines.launch

private enum class RootDestination {
    Welcome,
    Home,
    Downloads,
    Diagnostics,
    Settings,
    Studio,
    UiDiagnostics,
    Performance,
}

class MainActivity : ComponentActivity() {
    private val downloadLauncher by lazy { DownloadLauncher(applicationContext) }
    private val applicationServices by lazy { application as AHDownloadApplication }
    private val downloadWorkScheduler by lazy { applicationServices.downloadWorkScheduler }
    private val downloadRepository by lazy { applicationServices.downloadRepository }
    private val diagnosticLogger by lazy { applicationServices.diagnosticLogger }
    private val uiTraceLogger by lazy { applicationServices.uiTraceLogger }
    private val downloadLocationStore by lazy { DownloadLocationStore(applicationContext) }
    private val downloadPreferencesStore by lazy { DownloadPreferencesStore(applicationContext) }
    private val themePreferenceStore by lazy { ThemePreferenceStore(applicationContext) }
    private val favoritesStore by lazy { FavoritesStore(applicationContext) }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) downloadLocationStore.saveTreeUri(uri)
        }

    private var pendingSharedUrl by mutableStateOf<String?>(null)
    private var openDownloadsOnStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        extractSharedUrl(intent)?.let { pendingSharedUrl = it }
        openDownloadsOnStart = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true

        setContent {
            var themeMode by remember { mutableStateOf(themePreferenceStore.read()) }
            AHTheme(themeMode = themeMode) {
                AHRoot(
                    themeMode = themeMode,
                    onThemeChanged = { mode ->
                        themePreferenceStore.set(mode)
                        themeMode = mode
                    },
                    initialUrl = pendingSharedUrl,
                    logger = diagnosticLogger,
                    performanceLogStore = applicationServices.performanceLogStore,
                    onDownloadRequested = { candidate, title, sourcePageUrl, thumbnailUrl ->
                        requestNotificationPermissionIfNeeded()
                        downloadLauncher.enqueue(candidate, title, sourcePageUrl, thumbnailUrl)
                    },
                    onAudioOnlyRequested = { candidate, outputFormat, title, sourcePageUrl, thumbnailUrl ->
                        requestNotificationPermissionIfNeeded()
                        downloadLauncher.enqueueAudioExtraction(candidate, outputFormat, title, sourcePageUrl, thumbnailUrl)
                    },
                    onDeleteDownloadFile = ::deleteDownloadedFile,
                    onShareDownload = ::shareCompletedDownload,
                    onConsumeInitialUrl = { pendingSharedUrl = null },
                    onOpenYouTubeSession = ::openYouTubeSession,
                    uiTraceLogger = uiTraceLogger,
                    downloadRepository = downloadRepository,
                    onPauseDownload = downloadWorkScheduler::pause,
                    onResumeDownload = downloadWorkScheduler::resume,
                    onCancelDownload = downloadWorkScheduler::cancel,
                    onOpenDownload = ::openCompletedDownload,
                    onOpenDownloadFolder = ::openDownloadFolder,
                    downloadLocationStore = downloadLocationStore,
                    downloadPreferencesProvider = downloadPreferencesStore,
                    favoriteRepository = favoritesStore,
                    onExtractAudio = ::extractAndPublishAudio,
                    openDownloadsOnStart = openDownloadsOnStart,
                    onPickDownloadFolder = {
                        folderPicker.launch(downloadLocationStore.persistedUri())
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { pendingSharedUrl = it }
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) openDownloadsOnStart = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE,
            )
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun extractAndPublishAudio(record: DownloadRecord): Boolean {
        val result = MediaAudioExtractor(applicationContext).extractAndPublish(record)
        result.exceptionOrNull()?.let { error ->
            diagnosticLogger.log(
                DiagnosticLevel.WARNING,
                "STUDIO_AUDIO_EXTRACTION_FAILED",
                "تعذر استخراج الصوت من الملف المحلي",
                "studio.extract_audio",
                mapOf("task_id" to record.task.id),
                error,
            )
        }
        if (result.isSuccess) {
            diagnosticLogger.log(
                DiagnosticLevel.INFO,
                "STUDIO_AUDIO_EXTRACTION_COMPLETED",
                "تم استخراج الصوت الأصلي وحفظه",
                "studio.extract_audio",
                mapOf("task_id" to record.task.id),
                null,
            )
        }
        return result.isSuccess
    }

    private fun openCompletedDownload(record: DownloadRecord) {
        val destination = record.destinationUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: return

        val mimeType = contentResolver.getType(destination)
            ?: mimeTypeFor(record.task.displayName ?: record.task.destinationPath)

        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(destination, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.onFailure { error ->
            diagnosticLogger.log(
                level = DiagnosticLevel.WARNING,
                type = "download_open_failed",
                reason = "Unable to open completed download",
                operation = "main.open_download",
                context = mapOf(
                    "task_id" to record.task.id,
                    "mime_type" to mimeType,
                    "destination_uri_present" to "true",
                ),
                throwable = error,
            )
        }
    }

    private fun openDownloadFolder(record: DownloadRecord) {
        val treeUri = downloadLocationStore.persistedUri() ?: return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, treeUri).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.onFailure { error ->
            diagnosticLogger.log(
                DiagnosticLevel.WARNING,
                "DOWNLOAD_FOLDER_OPEN_FAILED",
                "تعذر فتح مجلد الحفظ",
                "main.open_download_folder",
                mapOf("task_id" to record.task.id),
                error,
            )
        }
    }

    private fun shareCompletedDownload(record: DownloadRecord) {
        val destination = record.destinationUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: return

        val mimeType = contentResolver.getType(destination)
            ?: mimeTypeFor(record.task.displayName ?: record.task.destinationPath)

        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, destination)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "مشاركة عبر",
                ),
            )
        }.onFailure { error ->
            diagnosticLogger.log(
                level = DiagnosticLevel.WARNING,
                type = "download_share_failed",
                reason = "Unable to share completed download",
                operation = "main.share_download",
                context = mapOf(
                    "task_id" to record.task.id,
                    "mime_type" to mimeType,
                ),
                throwable = error,
            )
        }
    }

    private fun deleteDownloadedFile(record: DownloadRecord): Boolean {
        val deleted = runCatching {
            val uri = record.destinationUri
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
            when {
                uri != null -> contentResolver.delete(uri, null, null) > 0
                else -> File(record.task.destinationPath).delete()
            }
        }.getOrDefault(false)

        if (deleted) {
            diagnosticLogger.log(
                DiagnosticLevel.INFO,
                "DOWNLOAD_FILE_DELETED",
                "تم حذف الملف المطلوب من الجهاز",
                "main.delete_download",
                mapOf("task_id" to record.task.id),
                null,
            )
            lifecycleScope.launch {
                applicationServices.downloadRepository.delete(record.task.id)
            }
        }
        return deleted
    }

    private fun mimeTypeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }

    private fun openYouTubeSession() {
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(YOUTUBE_URL),
                ),
            )
        }.onFailure { error ->
            diagnosticLogger.log(
                level = DiagnosticLevel.WARNING,
                type = "youtube_session_open_failed",
                reason = "Unable to open YouTube session",
                operation = "main.open_youtube_session",
                context = emptyMap(),
                throwable = error,
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "extra_open_downloads"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val YOUTUBE_URL = "https://www.youtube.com"
    }
}

@Composable
private fun AHRoot(
    initialUrl: String?,
    logger: PersistentDiagnosticLogger,
    performanceLogStore: PerformanceLogStore,
    onDownloadRequested: suspend (MediaCandidate, String?, String?, String?) -> DownloadEnqueueResult,
    onAudioOnlyRequested: suspend (MediaCandidate, AudioOutputFormat, String?, String?, String?) -> DownloadEnqueueResult,
    onDeleteDownloadFile: (DownloadRecord) -> Boolean,
    onShareDownload: (DownloadRecord) -> Unit,
    onConsumeInitialUrl: () -> Unit,
    onOpenYouTubeSession: () -> Unit,
    uiTraceLogger: PersistentUiTraceLogger,
    downloadRepository: com.ahdownload.domain.download.DownloadRepository,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (DownloadRecord) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownload: (DownloadRecord) -> Unit,
    onOpenDownloadFolder: (DownloadRecord) -> Unit,
    downloadLocationStore: DownloadLocationStore,
    downloadPreferencesProvider: com.ahdownload.core.common.DownloadPreferencesProvider,
    favoriteRepository: com.ahdownload.domain.favorites.FavoriteRepository,
    themeMode: AHThemeMode,
    onThemeChanged: (AHThemeMode) -> Unit,
    onExtractAudio: suspend (DownloadRecord) -> Boolean,
    onPickDownloadFolder: () -> Unit,
    openDownloadsOnStart: Boolean = false,
) {
    val history by downloadRepository.observeHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    var studioRecord by remember { mutableStateOf<DownloadRecord?>(null) }

    val activeDownloads = history.count {
        it.status in setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
        )
    }
    val backStack = remember {
        mutableStateListOf(
            if (initialUrl?.isNotBlank() == true) RootDestination.Home
            else RootDestination.Welcome,
        )
    }

    fun root(destination: RootDestination) {
        backStack.clear()
        backStack.add(destination)
    }

    fun push(destination: RootDestination) {
        if (backStack.lastOrNull() != destination) backStack.add(destination)
    }

    fun popOrHome() {
        if (backStack.size > 1) backStack.removeLast()
        else root(RootDestination.Home)
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl?.isNotBlank() == true) {
            root(RootDestination.Home)
        }
    }

    LaunchedEffect(openDownloadsOnStart) {
        if (openDownloadsOnStart) root(RootDestination.Downloads)
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLast()
    }

    CompositionLocalProvider(LocalDiagnosticUiTraceLogger provides uiTraceLogger) {
    when (backStack.last()) {
        RootDestination.Welcome -> WelcomeRoute(
            onContinue = { root(RootDestination.Home) },
            uiTraceLogger = uiTraceLogger,
        )
        RootDestination.Home -> HomeRoute(
            initialUrl = initialUrl,
            onDownloadRequested = onDownloadRequested,
            onAudioOnlyRequested = onAudioOnlyRequested,
            logger = logger,
            onOpenSettings = { root(RootDestination.Settings) },
            onOpenDownloads = { root(RootDestination.Downloads) },
            onInitialUrlConsumed = onConsumeInitialUrl,
            uiTraceLogger = uiTraceLogger,
            onCopyHomeTrace = uiTraceLogger::exportHomeText,
            onCopyResultCardTrace = uiTraceLogger::exportResultCardText,
            activeDownloads = activeDownloads,
            preferencesProvider = downloadPreferencesProvider,
            favoriteRepository = favoriteRepository,
            onAnalysisPerformance = { sample ->
                performanceLogStore.recordAnalysis(
                    operationId = sample.operationId,
                    platform = sample.platform,
                    startedAtEpochMs = sample.startedAtEpochMs,
                    durationMs = sample.durationMs,
                    outcome = sample.outcome,
                    candidateCount = sample.candidateCount,
                )
            },
        )
        RootDestination.Downloads -> DownloadsRoute(
            repository = downloadRepository,
            favoriteRepository = favoriteRepository,
            onPauseDownload = onPauseDownload,
            onResumeDownload = onResumeDownload,
            onCancelDownload = onCancelDownload,
            onOpenDownload = onOpenDownload,
            onShareDownload = onShareDownload,
            onOpenDownloadFolder = onOpenDownloadFolder,
            onOpenStudio = { record ->
                studioRecord = record
                push(RootDestination.Studio)
            },
            onDeleteDownloadFile = onDeleteDownloadFile,
            uiTraceLogger = uiTraceLogger,
            onBack = ::popOrHome,
            onNavigateHome = { root(RootDestination.Home) },
            onNavigateSettings = { root(RootDestination.Settings) },
            activeDownloads = activeDownloads,
        )
        RootDestination.Studio -> studioRecord?.let { record ->
            StudioRoute(
                record = record,
                onOpen = { onOpenDownload(record) },
                onShare = { onShareDownload(record) },
                onExtractAudio = onExtractAudio,
                onBack = {
                    studioRecord = null
                    popOrHome()
                },
                uiTraceLogger = uiTraceLogger,
            )
        } ?: run {
            root(RootDestination.Downloads)
        }
        RootDestination.Settings -> SettingsRoute(
            store = downloadLocationStore,
            diagnosticLogger = logger,
            performanceLogStore = performanceLogStore,
            onOpenPerformanceLog = { push(RootDestination.Performance) },
            themeMode = themeMode,
            onThemeChanged = onThemeChanged,
            onPickDownloadFolder = onPickDownloadFolder,
            onOpenDiagnostics = { push(RootDestination.Diagnostics) },
            onBack = ::popOrHome,
            onNavigateHome = { root(RootDestination.Home) },
            onNavigateDownloads = { root(RootDestination.Downloads) },
            uiTraceLogger = uiTraceLogger,
            activeDownloads = activeDownloads,
        )
        RootDestination.Performance -> PerformanceLogRoute(
            store = performanceLogStore,
            onBack = ::popOrHome,
        )
        RootDestination.Diagnostics -> DiagnosticsRoute(
            logger = logger,
            uiTraceLogger = uiTraceLogger,
            onBack = ::popOrHome,
        )
        RootDestination.UiDiagnostics -> DiagnosticsRoute(
            logger = logger,
            uiTraceLogger = uiTraceLogger,
            onBack = ::popOrHome,
        )
        }
    }
}
