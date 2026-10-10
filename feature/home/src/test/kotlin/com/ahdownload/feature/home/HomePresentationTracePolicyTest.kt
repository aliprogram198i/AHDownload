package com.ahdownload.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresentationTracePolicyTest {
    @Test
    fun errorOnlyStateIsNotReportedAsAResultPresentation() {
        assertFalse(shouldEmitSmartCenterResultPresented(hasResolution = false))
    }

    @Test
    fun actualResolutionIsReportedAsAResultPresentation() {
        assertTrue(shouldEmitSmartCenterResultPresented(hasResolution = true))
    }
}
