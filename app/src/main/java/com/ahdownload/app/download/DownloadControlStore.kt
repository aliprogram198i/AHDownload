package com.ahdownload.app.download

import android.content.Context

/** Tiny, synchronous control state used by WorkManager cancellation paths. No secrets are stored. */
class DownloadControlStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markPaused(taskId: String) {
        preferences.edit().putBoolean(key(taskId), true).apply()
    }

    fun clearPaused(taskId: String) {
        preferences.edit().remove(key(taskId)).apply()
    }

    fun isPaused(taskId: String): Boolean = preferences.getBoolean(key(taskId), false)

    private fun key(taskId: String): String = PREFIX + taskId

    private companion object {
        const val PREFS = "ahdownload_download_controls"
        const val PREFIX = "paused_"
    }
}
