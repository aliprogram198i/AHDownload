package com.ahdownload.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserRenderStatusClassifierTest {
    @Test
    fun visibleViewportSignalsAreReportedAsSignalsNotVerifiedRendering() {
        assertEquals(
            "CONTENT_SIGNALS_DETECTED",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "complete",
                hasVisibleContentSignals = true,
                hasAnyDomContentSignals = true,
            ),
        )
    }

    @Test
    fun lowConfidenceTextOrTinyImagesDoNotCountAsConfirmedPrimaryContent() {
        assertEquals(
            "CONTENT_VISIBILITY_UNCONFIRMED",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "complete",
                hasVisibleContentSignals = true,
                hasAnyDomContentSignals = true,
                hasPrimaryVisibleContent = false,
            ),
        )
    }

    @Test
    fun readableTextOrSubstantialMediaCanConfirmPrimaryContent() {
        assertEquals(
            "CONTENT_SIGNALS_DETECTED",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "complete",
                hasVisibleContentSignals = true,
                hasAnyDomContentSignals = true,
                hasPrimaryVisibleContent = true,
            ),
        )
    }

    @Test
    fun viewportElementsRejectedByHitTestingGetDedicatedDiagnosticState() {
        assertEquals(
            "CONTENT_OCCLUDED_OR_HIT_TEST_MISMATCH",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "complete",
                hasVisibleContentSignals = false,
                hasAnyDomContentSignals = true,
                hasPrimaryVisibleContent = false,
                hasOccludedViewportContent = true,
            ),
        )
    }

    @Test
    fun accessChallengeStillTakesPrecedenceOverHitTestSignals() {
        assertEquals(
            "POSSIBLE_ACCESS_CHALLENGE",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = true,
                readyState = "complete",
                hasVisibleContentSignals = false,
                hasAnyDomContentSignals = true,
                hasPrimaryVisibleContent = false,
                hasOccludedViewportContent = true,
            ),
        )
    }

    @Test
    fun hiddenOrOffscreenDomIsNotMisreportedAsVisibleContent() {
        assertEquals(
            "CONTENT_NOT_VISIBLE_IN_VIEWPORT",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "complete",
                hasVisibleContentSignals = false,
                hasAnyDomContentSignals = true,
            ),
        )
    }

    @Test
    fun emptyCompleteDocumentIsReportedAsPossiblyEmpty() {
        assertEquals(
            "POSSIBLY_EMPTY_PAGE",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "complete",
                hasVisibleContentSignals = false,
                hasAnyDomContentSignals = false,
            ),
        )
    }

    @Test
    fun accessChallengeTakesPrecedenceOverVisibleSignals() {
        assertEquals(
            "POSSIBLE_ACCESS_CHALLENGE",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = true,
                readyState = "complete",
                hasVisibleContentSignals = true,
                hasAnyDomContentSignals = true,
            ),
        )
    }

    @Test
    fun incompleteDomWithoutVisibleSignalsStaysNotReady() {
        assertEquals(
            "DOM_NOT_READY",
            BrowserRenderStatusClassifier.classify(
                challengeDetected = false,
                readyState = "interactive",
                hasVisibleContentSignals = false,
                hasAnyDomContentSignals = false,
            ),
        )
    }
}
