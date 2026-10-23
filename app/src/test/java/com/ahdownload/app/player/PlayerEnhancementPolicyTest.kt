package com.ahdownload.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEnhancementPolicyTest {
    @Test
    fun seekTargetsClampToDurationAndDoNotOverflow() {
        assertEquals(0L, PlayerEnhancementPolicy.seekTarget(3_000L, 0L, true))
        assertEquals(0L, PlayerEnhancementPolicy.seekTarget(4_000L, 60_000L, false))
        assertEquals(60_000L, PlayerEnhancementPolicy.seekTarget(55_000L, 60_000L, true))
        assertEquals(25_000L, PlayerEnhancementPolicy.seekTarget(35_000L, 60_000L, false))
        assertEquals(10_000L, PlayerEnhancementPolicy.seekTarget(-50L, 30_000L, true))
        assertEquals(30_000L, PlayerEnhancementPolicy.seekTarget(Long.MAX_VALUE, 30_000L, true))
        assertEquals(0L, PlayerEnhancementPolicy.seekTarget(Long.MIN_VALUE, 30_000L, false))
    }

    @Test
    fun aspectRatiosUseSafeFallbacks() {
        assertEquals(16f / 9f, PlayerEnhancementPolicy.aspectRatio(PlayerAspectMode.FIT, Float.NaN), 0.001f)
        assertEquals(16f / 9f, PlayerEnhancementPolicy.aspectRatio(PlayerAspectMode.WIDE_16_9, 1f), 0.001f)
        assertEquals(4f / 3f, PlayerEnhancementPolicy.aspectRatio(PlayerAspectMode.CLASSIC_4_3, 2f), 0.001f)
        assertEquals(1.5f, PlayerEnhancementPolicy.aspectRatio(PlayerAspectMode.FIT, 1.5f), 0.001f)
        assertEquals(16f / 9f, PlayerEnhancementPolicy.aspectRatio(PlayerAspectMode.FIT, 99f), 0.001f)
    }

    @Test
    fun playbackSpeedsAreUniqueAndSupported() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), PlayerEnhancementPolicy.supportedSpeeds)
        assertEquals(PlayerEnhancementPolicy.supportedSpeeds.size, PlayerEnhancementPolicy.supportedSpeeds.toSet().size)
    }

    @Test
    fun queueMovesAreSafeAndPreserveEntries() {
        assertEquals(listOf("b", "c", "a"), PlayerEnhancementPolicy.moveItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("a", "b", "c"), PlayerEnhancementPolicy.moveItem(listOf("a", "b", "c"), -1, 2))
        assertEquals(listOf("a", "b", "c"), PlayerEnhancementPolicy.moveItem(listOf("a", "b", "c"), 1, 1))
    }
}
