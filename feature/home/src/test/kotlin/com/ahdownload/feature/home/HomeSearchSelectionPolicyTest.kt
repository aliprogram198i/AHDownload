package com.ahdownload.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSearchSelectionPolicyTest {
    @Test
    fun staleSearchResponsesAreRejected() {
        assertFalse(isCurrentSearchRequest(requestGeneration = 2L, activeGeneration = 3L))
    }

    @Test
    fun currentSearchResponsesAreAccepted() {
        assertTrue(isCurrentSearchRequest(requestGeneration = 3L, activeGeneration = 3L))
    }

    @Test
    fun selectionStopsAtTheBatchLimit() {
        val selected = (1..MAX_SEARCH_BATCH_SELECTION).map { "video-$it" }.toSet()

        assertFalse(canAddSearchSelection(selected, "video-new"))
    }

    @Test
    fun alreadySelectedItemsRemainTogglableAtTheLimit() {
        val selected = (1..MAX_SEARCH_BATCH_SELECTION).map { "video-$it" }.toSet()

        assertTrue(canAddSearchSelection(selected, "video-1"))
    }

    @Test
    fun selectionCanGrowBelowTheLimit() {
        val selected = (1 until MAX_SEARCH_BATCH_SELECTION).map { "video-$it" }.toSet()

        assertTrue(canAddSearchSelection(selected, "video-new"))
    }
}
