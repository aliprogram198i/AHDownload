package com.ahdownload.app.browser

/**
 * Rejects asynchronous WebView results that no longer belong to the active
 * navigation or whose callback URL no longer matches the document in the view.
 *
 * This intentionally compares the URL strings as supplied by WebView: normalizing
 * away query parameters or paths could incorrectly accept a callback from another
 * document.
 */
internal object BrowserNavigationCallbackGuard {
    fun isCurrent(
        callbackNavigationId: String,
        activeNavigationId: String,
        callbackUrl: String,
        activeWebViewUrl: String?,
        activeBrowserUrl: String?,
    ): Boolean {
        if (callbackNavigationId.isBlank() || activeNavigationId.isBlank()) return false
        if (callbackNavigationId != activeNavigationId) return false

        val callback = callbackUrl.trim()
        val active = activeWebViewUrl?.trim().orEmpty()
        val browserStateUrl = activeBrowserUrl?.trim().orEmpty()
        if (callback.isEmpty() || active.isEmpty() || browserStateUrl.isEmpty()) return false

        return callback == active && callback == browserStateUrl
    }
}
