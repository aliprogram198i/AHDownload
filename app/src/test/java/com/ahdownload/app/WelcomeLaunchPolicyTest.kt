package com.ahdownload.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomeLaunchPolicyTest {

    @Test
    fun sharedUrlTakesPriorityOverFirstRunWelcome() {
        assertEquals(
            InitialAppDestination.HOME,
            WelcomeLaunchPolicy.resolve(
                showWelcomeOnLaunch = true,
                hasIncomingUrl = true,
                openDownloadsOnStart = false,
            ),
        )
    }

    @Test
    fun explicitDownloadsRequestTakesPriorityOverWelcome() {
        assertEquals(
            InitialAppDestination.DOWNLOADS,
            WelcomeLaunchPolicy.resolve(
                showWelcomeOnLaunch = true,
                hasIncomingUrl = false,
                openDownloadsOnStart = true,
            ),
        )
    }

    @Test
    fun welcomeIsSelectedForAnOrdinaryFirstLaunch() {
        assertEquals(
            InitialAppDestination.WELCOME,
            WelcomeLaunchPolicy.resolve(
                showWelcomeOnLaunch = true,
                hasIncomingUrl = false,
                openDownloadsOnStart = false,
            ),
        )
    }

    @Test
    fun returningUserGoesDirectlyToHome() {
        assertEquals(
            InitialAppDestination.HOME,
            WelcomeLaunchPolicy.resolve(
                showWelcomeOnLaunch = false,
                hasIncomingUrl = false,
                openDownloadsOnStart = false,
            ),
        )
    }
}
