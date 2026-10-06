package com.ahdownload.app

import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ahdownload.app.diagnostics.DiagnosticsRoute
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.app.download.DownloadLauncher
import com.ahdownload.core.designsystem.AHTheme
import com.ahdownload.feature.home.HomeRoute
import com.ahdownload.feature.welcome.WelcomeRoute

private enum class RootDestination { Welcome, Home, Diagnostics }

class MainActivity : ComponentActivity() {
    private val downloadLauncher by lazy { DownloadLauncher(applicationContext) }
    private val diagnosticLogger by lazy { (application as AHDownloadApplication).diagnosticLogger }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        val sharedUrl = intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        setContent {
            AHTheme {
                AHRoot(
                    initialUrl = sharedUrl,
                    logger = diagnosticLogger,
                    onDownloadRequested = { candidate, title ->
                        downloadLauncher.enqueue(candidate, title)
                    },
                    onOpenYouTubeSession = {
                        try {
                            val youtubeIntent = Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com")
                            )
                            startActivity(youtubeIntent)
                        } catch (_: Exception) { }
                    },
                )
            }
        }
    }
}

@Composable
private fun AHRoot(
    initialUrl: String?,
    logger: PersistentDiagnosticLogger,
    onDownloadRequested: suspend (com.ahdownload.domain.resolver.MediaCandidate, String?) -> Boolean,
    onOpenYouTubeSession: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(RootDestination.Welcome) }

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
            )
            RootDestination.Diagnostics -> DiagnosticsRoute(
                logger = logger,
                onBack = { destination = RootDestination.Home },
            )
        }
    }
}
