package com.ahdownload.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionPolicyTest {
    @Test
    fun missingOrInvalidSnapshotStartsAtBeginning() {
        assertEquals(0L, restorablePlaybackPosition(null))
        assertEquals(0L, restorablePlaybackPosition(PlaybackPositionSnapshot(-1L, 60_000L)))
        assertEquals(0L, restorablePlaybackPosition(PlaybackPositionSnapshot(3_000L, 0L)))
        assertEquals(0L, restorablePlaybackPosition(PlaybackPositionSnapshot(0L, 60_000L)))
    }

    @Test
    fun restoresPositionWhenThereIsMeaningfulContentRemaining() {
        assertEquals(
            23_400L,
            restorablePlaybackPosition(PlaybackPositionSnapshot(23_400L, 60_000L)),
        )
    }

    @Test
    fun startsFromBeginningWhenSavedPositionIsAtOrNearEnd() {
        assertEquals(
            0L,
            restorablePlaybackPosition(PlaybackPositionSnapshot(59_000L, 60_000L)),
        )
        assertEquals(
            0L,
            restorablePlaybackPosition(PlaybackPositionSnapshot(60_000L, 60_000L)),
        )
        assertEquals(
            0L,
            restorablePlaybackPosition(PlaybackPositionSnapshot(300L, 1_000L)),
        )
    }

    @Test
    fun positionsBeyondDurationAreRejectedByEndBoundary() {
        assertEquals(
            0L,
            restorablePlaybackPosition(PlaybackPositionSnapshot(70_000L, 60_000L)),
        )
    }
}
