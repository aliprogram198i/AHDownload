package com.ahdownload.app

import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ahdownload.app.download.DownloadLauncher
import com.ahdownload.core.designsystem.AHTheme
import com.ahdownload.feature.home.HomeRoute
import com.ahdownload.feature.welcome.WelcomeRoute

private enum class RootDestination {
    Welcome,
    Home,
}

class MainActivity : ComponentActivity() {
    private val downloadLauncher by lazy { DownloadLauncher(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AHTheme {
                AHRoot(
                    onDownloadRequested = { candidate, title ->
                        downloadLauncher.enqueue(candidate, title)
                    },
                )
            }
        }
    }
}

@Composable
private fun AHRoot(
    onDownloadRequested: suspend (com.ahdownload.domain.resolver.MediaCandidate, String?) -> Boolean,
) {
    var destination by rememberSaveable { mutableStateOf(RootDestination.Welcome) }
    val downloadCallback = remember(onDownloadRequested) { onDownloadRequested }

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
                onDownloadRequested = downloadCallback,
            )
        }
    }
}
