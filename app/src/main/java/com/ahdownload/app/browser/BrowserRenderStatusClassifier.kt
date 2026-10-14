package com.ahdownload.app.browser

/**
 * Conservative classification of page-render evidence.
 *
 * DOM existence and document load completion do not prove that content is
 * visible in the WebView viewport. Keep off-screen/hidden DOM content distinct
 * from visible viewport signals.
 */
internal object BrowserRenderStatusClassifier {
    fun classify(
        challengeDetected: Boolean,
        readyState: String,
        hasVisibleContentSignals: Boolean,
        hasAnyDomContentSignals: Boolean,
        hasPrimaryVisibleContent: Boolean = hasVisibleContentSignals,
        hasOccludedViewportContent: Boolean = false,
    ): String = when {
        challengeDetected -> "POSSIBLE_ACCESS_CHALLENGE"
        // If hit testing says viewport content is fully covered/rejected, do not
        // let weaker DOM signals override the stronger occlusion evidence.
        hasOccludedViewportContent -> "CONTENT_OCCLUDED_OR_HIT_TEST_MISMATCH"
        hasPrimaryVisibleContent -> "CONTENT_SIGNALS_DETECTED"
        hasVisibleContentSignals -> "CONTENT_VISIBILITY_UNCONFIRMED"
        readyState == "complete" && hasAnyDomContentSignals -> "CONTENT_NOT_VISIBLE_IN_VIEWPORT"
        readyState == "complete" -> "POSSIBLY_EMPTY_PAGE"
        else -> "DOM_NOT_READY"
    }
}
