package com.ahdownload.app.download

import android.content.Context
import java.util.UUID

/** A control intent plus a unique generation token, used to reject stale worker callbacks. */
data class DownloadControlIntentSnapshot(
    val intent: String,
    val token: String,
)

/**
 * Synchronous per-task control intent used to distinguish user pause/cancel requests from
 * stale worker cancellation callbacks after a newer action such as resume.
 * This store contains no media URLs, cookies, or credentials.
 */
class DownloadControlStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markPaused(taskId: String): DownloadControlIntentSnapshot =
        writeIntent(taskId, INTENT_PAUSED, paused = true)

    fun markCancelled(taskId: String): DownloadControlIntentSnapshot =
        writeIntent(taskId, INTENT_CANCELLED, paused = false)

    fun markActive(taskId: String): DownloadControlIntentSnapshot =
        writeIntent(taskId, INTENT_ACTIVE, paused = false)

    /** Clears both current and legacy control keys; use markActive when scheduling work. */
    fun clearPaused(taskId: String) {
        preferences.edit()
            .remove(pausedKey(taskId))
            .remove(intentKey(taskId))
            .remove(tokenKey(taskId))
            .apply()
    }

    fun isPaused(taskId: String): Boolean =
        preferences.getString(intentKey(taskId), null)?.let { it == INTENT_PAUSED }
            ?: preferences.getBoolean(pausedKey(taskId), false)

    /** Current explicit user intent; null means no current control request is recorded. */
    fun intent(taskId: String): String? = preferences.getString(intentKey(taskId), null)

    /**
     * Read the control intent and generation together. The legacy fallback lets already
     * persisted pause requests from older app versions continue to be interpreted correctly.
     */
    fun snapshot(taskId: String): DownloadControlIntentSnapshot? {
        val intent = preferences.getString(intentKey(taskId), null)
        if (intent != null) {
            return DownloadControlIntentSnapshot(
                intent = intent,
                token = preferences.getString(tokenKey(taskId), null) ?: LEGACY_INTENT_TOKEN,
            )
        }
        return if (preferences.getBoolean(pausedKey(taskId), false)) {
            DownloadControlIntentSnapshot(INTENT_PAUSED, LEGACY_PAUSED_TOKEN)
        } else {
            null
        }
    }

    /** True only while no newer per-task control action has superseded this snapshot. */
    fun isCurrent(taskId: String, expected: DownloadControlIntentSnapshot): Boolean =
        snapshot(taskId) == expected

    private fun writeIntent(
        taskId: String,
        intent: String,
        paused: Boolean,
    ): DownloadControlIntentSnapshot {
        val token = UUID.randomUUID().toString()
        preferences.edit()
            .putBoolean(pausedKey(taskId), paused)
            .putString(intentKey(taskId), intent)
            .putString(tokenKey(taskId), token)
            .apply()
        return DownloadControlIntentSnapshot(intent, token)
    }

    private fun pausedKey(taskId: String): String = PAUSED_PREFIX + taskId
    private fun intentKey(taskId: String): String = INTENT_PREFIX + taskId
    private fun tokenKey(taskId: String): String = TOKEN_PREFIX + taskId

    private companion object {
        const val PREFS = "ahdownload_download_controls"
        const val PAUSED_PREFIX = "paused_"
        const val INTENT_PREFIX = "intent_"
        const val TOKEN_PREFIX = "intent_token_"
        const val INTENT_ACTIVE = "ACTIVE"
        const val INTENT_PAUSED = "PAUSED"
        const val INTENT_CANCELLED = "CANCELLED"
        const val LEGACY_INTENT_TOKEN = "legacy-explicit-intent"
        const val LEGACY_PAUSED_TOKEN = "legacy-paused-flag"
    }
}
