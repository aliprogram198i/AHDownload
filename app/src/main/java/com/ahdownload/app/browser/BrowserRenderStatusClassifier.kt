package com.ahdownload.app.browser

/**
 * Conservative classification of page-render evidence.
 *
 * DOM existence and document load completion do not prove that content is
 * visible in the WebView viewport. Hit-testing is also not definitive proof
 * of occlusion: CSS pointer-events, clickable wrappers and WebView hit-test
 * differences can reject an otherwise rendered element. Keep that ambiguity
 * separate from a confirmed primary-content signal.
 */
internal object BrowserRenderStatusClassifier {
    fun classify(
        challengeDetected: Boolean,
        readyState: String,
        hasVisibleContentSignals: Boolean,
        hasAnyDomContentSignals: Boolean,
        hasPrimaryVisibleContent: Boolean = hasVisibleContentSignals,
        hasHitTestRejectedContent: Boolean = false,
    ): String = when {
        challengeDetected -> "POSSIBLE_ACCESS_CHALLENGE"
        // Readable text/substantial media is stronger evidence than an
        // inconsistent elementFromPoint result. Do not downgrade it to occlusion.
        hasPrimaryVisibleContent -> "CONTENT_SIGNALS_DETECTED"
        // A rejected hit-test alone cannot establish that a layer really covers
        // the page. Report uncertainty unless independent evidence proves occlusion.
        hasHitTestRejectedContent -> "CONTENT_VISIBILITY_UNCONFIRMED"
        hasVisibleContentSignals -> "CONTENT_VISIBILITY_UNCONFIRMED"
        readyState == "complete" && hasAnyDomContentSignals -> "CONTENT_NOT_VISIBLE_IN_VIEWPORT"
        readyState == "complete" -> "POSSIBLY_EMPTY_PAGE"
        else -> "DOM_NOT_READY"
    }
}
