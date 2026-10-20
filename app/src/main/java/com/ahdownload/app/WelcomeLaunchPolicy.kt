package com.ahdownload.app

/**
 * Keeps externally requested destinations ahead of onboarding so shared links and
 * explicit requests to open Downloads are never delayed by the welcome screen.
 */
internal enum class InitialAppDestination {
    WELCOME,
    HOME,
    DOWNLOADS,
}

internal object WelcomeLaunchPolicy {
    fun resolve(
        showWelcomeOnLaunch: Boolean,
        hasIncomingUrl: Boolean,
        openDownloadsOnStart: Boolean,
    ): InitialAppDestination = when {
        hasIncomingUrl -> InitialAppDestination.HOME
        openDownloadsOnStart -> InitialAppDestination.DOWNLOADS
        showWelcomeOnLaunch -> InitialAppDestination.WELCOME
        else -> InitialAppDestination.HOME
    }
}
