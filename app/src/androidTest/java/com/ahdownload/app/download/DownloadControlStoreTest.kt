package com.ahdownload.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadControlStoreTest {
    private lateinit var context: Context
    private lateinit var store: DownloadControlStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ahdownload_download_controls", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = DownloadControlStore(context)
    }

    @Test
    fun explicitPauseAndCancelIntentsAreDistinguishable() {
        store.markPaused("task-a")
        assertTrue(store.isPaused("task-a"))
        assertEquals("PAUSED", store.intent("task-a"))

        store.markCancelled("task-a")
        assertFalse(store.isPaused("task-a"))
        assertEquals("CANCELLED", store.intent("task-a"))
    }

    @Test
    fun resumeClearsOlderPauseOrCancelIntent() {
        val pause = store.markPaused("task-b")
        store.markActive("task-b")
        assertFalse(store.isPaused("task-b"))
        assertEquals(null, store.intent("task-b"))
        assertFalse(store.isCurrent("task-b", pause))

        val cancellation = store.markCancelled("task-b")
        store.markActive("task-b")
        assertEquals(null, store.intent("task-b"))
        assertFalse(store.isCurrent("task-b", cancellation))
    }

    @Test
    fun legacyPausedFlagRemainsReadableWithoutExplicitIntent() {
        context.getSharedPreferences("ahdownload_download_controls", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("paused_legacy-task", true)
            .commit()

        assertTrue(store.isPaused("legacy-task"))
        assertEquals(null, store.intent("legacy-task"))
        assertEquals("PAUSED", store.snapshot("legacy-task")?.intent)
    }

    @Test
    fun newerPauseInvalidatesOlderPauseGenerationEvenWhenValueRepeats() {
        val firstPause = store.markPaused("task-c")
        store.markActive("task-c")
        val secondPause = store.markPaused("task-c")

        assertEquals("PAUSED", secondPause.intent)
        assertFalse(store.isCurrent("task-c", firstPause))
        assertTrue(store.isCurrent("task-c", secondPause))
    }

    @Test
    fun clearControlRemovesExplicitAndLegacyState() {
        store.markPaused("task-d")
        store.clearPaused("task-d")

        assertFalse(store.isPaused("task-d"))
        assertEquals(null, store.snapshot("task-d"))

        context.getSharedPreferences("ahdownload_download_controls", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("paused_legacy-cleanup", true)
            .commit()
        assertTrue(store.isPaused("legacy-cleanup"))

        store.clearPaused("legacy-cleanup")
        assertFalse(store.isPaused("legacy-cleanup"))
        assertEquals(null, store.snapshot("legacy-cleanup"))
    }
}
