package com.ahdownload.app.download

import android.content.Context

/**
 * Synchronous per-task control intent used to distinguish a user pause/cancel from
 * a stale worker cancellation after the user has already resumed the task.
 * This store contains no media URLs, cookies, or credentials.
 */
class DownloadControlStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markPaused(taskId: String) {
        preferences.edit()
            .putBoolean(pausedKey(taskId), true)
            .putString(intentKey(taskId), INTENT_PAUSED)
            .apply()
    }

    fun markCancelled(taskId: String) {
        preferences.edit()
            .putBoolean(pausedKey(taskId), false)
            .putString(intentKey(taskId), INTENT_CANCELLED)
            .apply()
    }

    fun markActive(taskId: String) {
        preferences.edit()
            .putBoolean(pausedKey(taskId), false)
            .putString(intentKey(taskId), INTENT_ACTIVE)
            .apply()
    }

    /** Backward-compatible alias for callers that clear a pause before enqueueing. */
    fun clearPaused(taskId: String) = markActive(taskId)

    fun isPaused(taskId: String): Boolean =
        preferences.getString(intentKey(taskId), null)?.let { it == INTENT_PAUSED }
            ?: preferences.getBoolean(pausedKey(taskId), false)

    /** Current explicit user intent; null means no user control request is recorded. */
    fun intent(taskId: String): String? = preferences.getString(intentKey(taskId), null)

    private fun pausedKey(taskId: String): String = PAUSED_PREFIX + taskId
    private fun intentKey(taskId: String): String = INTENT_PREFIX + taskId

    private companion object {
        const val PREFS = "ahdownload_download_controls"
        const val PAUSED_PREFIX = "paused_"
        const val INTENT_PREFIX = "intent_"
        const val INTENT_ACTIVE = "ACTIVE"
        const val INTENT_PAUSED = "PAUSED"
        const val INTENT_CANCELLED = "CANCELLED"
    }
}
