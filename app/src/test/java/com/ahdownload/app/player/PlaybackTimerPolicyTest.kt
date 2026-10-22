package com.ahdownload.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimerPolicyTest {
    @Test
    fun exposesOnlySupportedTimerPresets() {
        assertEquals(listOf(5, 10, 15, 30, 45, 60), PlaybackTimerPolicy.presetMinutes)
    }

    @Test
    fun computesDeadlineFromElapsedRealtime() {
        assertEquals(1_200_000L, PlaybackTimerPolicy.deadlineAfterMinutes(900_000L, 5))
    }

    @Test
    fun rejectsUnsupportedDurationAndInvalidClock() {
        assertNull(PlaybackTimerPolicy.deadlineAfterMinutes(1_000L, 7))
        assertNull(PlaybackTimerPolicy.deadlineAfterMinutes(-1L, 5))
    }

    @Test
    fun roundsRemainingTimeUpAndNeverReturnsNegative() {
        assertEquals(2L, PlaybackTimerPolicy.remainingSeconds(2_001L, 1_000L))
        assertEquals(1L, PlaybackTimerPolicy.remainingSeconds(1_001L, 1_000L))
        assertEquals(0L, PlaybackTimerPolicy.remainingSeconds(999L, 1_000L))
        assertEquals(0L, PlaybackTimerPolicy.remainingSeconds(0L, 1_000L))
        assertTrue(PlaybackTimerPolicy.remainingSeconds(3_000L, 4_000L) >= 0L)
    }

    @Test
    fun rejectsDeadlineOverflow() {
        assertNull(PlaybackTimerPolicy.deadlineAfterMinutes(Long.MAX_VALUE - 1L, 5))
    }
}
