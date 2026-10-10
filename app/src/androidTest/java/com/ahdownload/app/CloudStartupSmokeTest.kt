package com.ahdownload.app

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudStartupSmokeTest {

    @Test(timeout = 90_000)
    fun mainActivityLaunchesAndCreatesContentView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity must not be finishing after launch", activity.isFinishing)
                assertNotNull(
                    "The activity must create its Android content root",
                    activity.findViewById<View>(android.R.id.content),
                )
            }
        }
    }
}
