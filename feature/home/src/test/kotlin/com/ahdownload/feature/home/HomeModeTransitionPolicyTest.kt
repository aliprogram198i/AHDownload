package com.ahdownload.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModeTransitionPolicyTest {
    @Test
    fun repeatedLinkModeSynchronizationIsSkipped() {
        assertTrue(shouldSkipLinkModeSynchronization(HomeMode.Link))
    }

    @Test
    fun switchingFromSearchToLinkIsNotSkipped() {
        assertFalse(shouldSkipLinkModeSynchronization(HomeMode.Search))
    }
}
