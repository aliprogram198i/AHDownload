package com.ahdownload.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ahdownload.app.diagnostics.DiagnosticsRoute
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.app.download.DownloadLauncher
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.app.settings.SettingsRoute
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.designsystem.AHTheme
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.feature.home.HomeRoute
import com.ahdownload.feature.welcome.WelcomeRoute

private enum class RootDestination { Welcome, Home, Diagnostics, Settings }

class MainActivity : ComponentActivity() {
    private val downloadLauncher by lazy { DownloadLauncher(applicationContext) }
    private val diagnosticLogger by lazy { (application as AHDownloadApplication).diagnosticLogger }
    private val downloadLocationStore by lazy { DownloadLocationStore(applicationContext) }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) downloadLocationStore.saveTreeUri(uri)
    }
    private var pendingSharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        extractSharedUrl(intent)?.let { pendingSharedUrl = it }

        setContent {
            AHTheme {
                AHRoot(
                    initialUrl = pendingSharedUrl,
                    logger = diagnosticLogger,
                    onDownloadRequested = { candidate, title ->
                        downloadLauncher.enqueue(candidate, title)
                    },
                    onOpenYouTubeSession = ::openYouTubeSession,
                    downloadLocationStore = downloadLocationStore,
                    onPickDownloadFolder = { folderPicker.launch(downloadLocationStore.persistedUri()) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { pendingSharedUrl = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE,
            )
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

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
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val YOUTUBE_URL = "https://www.youtube.com"
    }
}

@Composable
private fun AHRoot(
    initialUrl: String?,
    logger: PersistentDiagnosticLogger,
    onDownloadRequested: suspend (MediaCandidate, String?) -> Boolean,
    onOpenYouTubeSession: () -> Unit,
    downloadLocationStore: DownloadLocationStore,
    onPickDownloadFolder: () -> Unit,
) {
    var destination by remember {
        mutableStateOf(
            if (initialUrl?.isNotBlank() == true) RootDestination.Home
            else RootDestination.Welcome,
        )
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl?.isNotBlank() == true) {
            destination = RootDestination.Home
        }
    }

    AnimatedContent(
        targetState = destination,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "rootDestination",
    ) { current ->
        when (current) {
            RootDestination.Welcome -> WelcomeRoute(
                onContinue = { destination = RootDestination.Home },
            )
            RootDestination.Home -> HomeRoute(
                initialUrl = initialUrl,
                onDownloadRequested = onDownloadRequested,
                logger = logger,
                onOpenDiagnostics = { destination = RootDestination.Diagnostics },
                onOpenYouTubeSession = onOpenYouTubeSession,
                onOpenSettings = { destination = RootDestination.Settings },
            )
            RootDestination.Settings -> SettingsRoute(
                store = downloadLocationStore,
                onPickDownloadFolder = onPickDownloadFolder,
                onBack = { destination = RootDestination.Home },
            )
            RootDestination.Diagnostics -> DiagnosticsRoute(
                logger = logger,
                onBack = { destination = RootDestination.Home },
            )
        }
    }
}
