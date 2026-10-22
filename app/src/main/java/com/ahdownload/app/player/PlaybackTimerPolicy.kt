package com.ahdownload.app.player

/**
 * Pure policy for the local player's sleep timer. Uses elapsed realtime at the call site
 * so wall-clock changes cannot extend or prematurely end a playback timer.
 */
internal object PlaybackTimerPolicy {
    val presetMinutes: List<Int> = listOf(5, 10, 15, 30, 45, 60)

    fun deadlineAfterMinutes(nowElapsedMs: Long, minutes: Int): Long? {
        if (nowElapsedMs < 0L || minutes !in presetMinutes) return null
        val durationMs = minutes.toLong() * 60_000L
        if (Long.MAX_VALUE - nowElapsedMs < durationMs) return null
        return nowElapsedMs + durationMs
    }

    fun remainingSeconds(deadlineElapsedMs: Long, nowElapsedMs: Long): Long {
        if (deadlineElapsedMs <= 0L || nowElapsedMs < 0L) return 0L
        val remainingMs = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        return if (remainingMs == 0L) 0L else (remainingMs + 999L) / 1_000L
    }
}
