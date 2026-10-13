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
    ): String = when {
        challengeDetected -> "POSSIBLE_ACCESS_CHALLENGE"
        hasVisibleContentSignals -> "CONTENT_SIGNALS_DETECTED"
        readyState == "complete" && hasAnyDomContentSignals -> "CONTENT_NOT_VISIBLE_IN_VIEWPORT"
        readyState == "complete" -> "POSSIBLY_EMPTY_PAGE"
        else -> "DOM_NOT_READY"
    }
}
