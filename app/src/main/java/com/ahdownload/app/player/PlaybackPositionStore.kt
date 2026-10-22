package com.ahdownload.app.player

import android.content.Context

/**
 * Stores only local playback offsets and durations, keyed by the app's stable download task ID.
 * No media URL, file content, cookie, or credential is persisted here.
 */
internal class PlaybackPositionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(taskId: String): PlaybackPositionSnapshot? {
        if (taskId.isBlank()) return null
        val positionMs = preferences.getLong(positionKey(taskId), INVALID_VALUE)
        val durationMs = preferences.getLong(durationKey(taskId), INVALID_VALUE)
        if (positionMs < 0L || durationMs <= 0L) return null
        return PlaybackPositionSnapshot(positionMs = positionMs, durationMs = durationMs)
    }

    fun write(taskId: String, positionMs: Long, durationMs: Long) {
        if (taskId.isBlank() || positionMs < 0L || durationMs <= 0L) return
        preferences.edit()
            .putLong(positionKey(taskId), positionMs.coerceAtMost(durationMs))
            .putLong(durationKey(taskId), durationMs)
            .apply()
    }

    fun clear(taskId: String) {
        if (taskId.isBlank()) return
        preferences.edit()
            .remove(positionKey(taskId))
            .remove(durationKey(taskId))
            .apply()
    }

    private fun positionKey(taskId: String) = "position_ms:$taskId"
    private fun durationKey(taskId: String) = "duration_ms:$taskId"

    private companion object {
        const val PREFERENCES_NAME = "ahdownload_local_playback_positions"
        const val INVALID_VALUE = -1L
    }
}

internal data class PlaybackPositionSnapshot(
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Do not resume at the very end of a file: doing so may leave the player in ENDED state with
 * no useful content to play. Invalid or missing snapshots safely start from the beginning.
 */
internal fun restorablePlaybackPosition(snapshot: PlaybackPositionSnapshot?): Long {
    snapshot ?: return 0L
    val positionMs = snapshot.positionMs
    val durationMs = snapshot.durationMs
    if (positionMs <= 0L || durationMs <= 0L) return 0L
    val resumeBoundary = (durationMs - PLAYBACK_END_GUARD_MS).coerceAtLeast(0L)
    return positionMs.takeIf { it < resumeBoundary }?.coerceAtMost(durationMs) ?: 0L
}

private const val PLAYBACK_END_GUARD_MS = 1_500L
