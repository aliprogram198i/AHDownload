package com.ahdownload.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class WebViewMediaSnapshot(
    val cookies: String?,
    val title: String?,
    val mediaUrls: List<String>
)

/**
 * Loads a platform page in Android WebView and captures both session cookies
 * and media resource URLs. No cookie value is logged or returned to telemetry.
 */
class WebViewSessionBridge(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun cookiesFor(url: String, timeoutMs: Long = 25_000L): String? =
        snapshotFor(url, timeoutMs).cookies

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun snapshotFor(url: String, timeoutMs: Long = 25_000L): WebViewMediaSnapshot =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var timeoutRunnable: Runnable? = null
            val capturedUrls = linkedSetOf<String>()

            fun addCandidate(raw: String?) {
                val value = raw?.trim().orEmpty()
                if (value.isBlank()) return
                val normalized = runCatching {
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        value
                    } else {
                        java.net.URI(url).resolve(value).toString()
                    }
                }.getOrNull() ?: return
                if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                    capturedUrls.add(normalized)
                }
            }

            fun finish(value: WebViewMediaSnapshot) {
                if (finished) return
                finished = true
                timeoutRunnable?.let(main::removeCallbacks)
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(value)
            }

            fun currentCookies(): String? =
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

            fun fallbackSnapshot(): WebViewMediaSnapshot =
                WebViewMediaSnapshot(currentCookies(), null, capturedUrls.take(32))

            fun inspect(view: WebView, attempt: Int = 1) {
                val script = """
                    (function() {
                      const urls = new Set();
                      const add = (v) => {
                        if (!v) return;
                        try { v = new URL(v, location.href).href; } catch (_) {}
                        if (/^https?:\/\//i.test(v)) urls.add(v);
                      };
                      document.querySelectorAll('video').forEach(v => {
                        add(v.currentSrc);
                        add(v.src);
                      });
                      document.querySelectorAll('video source, source').forEach(s => add(s.src));
                      document.querySelectorAll(
                        'meta[property="og:video"], meta[property="og:video:secure_url"], ' +
                        'meta[name="twitter:player:stream"]'
                      ).forEach(m => add(m.content));
                      try {
                        performance.getEntriesByType('resource').forEach(e => {
                          const n = e.name || '';
                          if (/\.(?:mp4|m4v|webm|mov|m3u8)(?:[?#]|$)/i.test(n) ||
                              /\/(?:video|playback|stream)(?:[/?]|$)/i.test(n) ||
                              /(cdninstagram|fbcdn|scontent)/i.test(n)) add(n);
                        });
                      } catch (_) {}
                      try {
                        const html = document.documentElement
                          ? (document.documentElement.outerHTML || '') : '';
                        const matches = html.match(/https?:\/\/[^"'<>\s]+/gi) || [];
                        matches.forEach(add);
                      } catch (_) {}
                      return JSON.stringify({
                        title: document.title || null,
                        urls: Array.from(urls).slice(0, 32)
                      });
                    })();
                """.trimIndent()

                view.evaluateJavascript(script) { raw ->
                    val parsed = runCatching {
                        val decoded = org.json.JSONTokener(raw ?: "null").nextValue()
                        if (decoded !is String) return@runCatching null
                        org.json.JSONObject(decoded)
                    }.getOrNull()

                    val title = parsed?.optString("title")?.takeIf { it.isNotBlank() }
                    parsed?.optJSONArray("urls")?.let { array ->
                        for (i in 0 until array.length()) addCandidate(array.optString(i))
                    }

                    if (capturedUrls.isNotEmpty() || attempt >= 3) {
                        finish(WebViewMediaSnapshot(currentCookies(), title, capturedUrls.take(32)))
                    } else {
                        main.postDelayed({ inspect(view, attempt + 1) }, 2_000L)
                    }
                }
            }

            val timeout = Runnable { finish(fallbackSnapshot()) }
            timeoutRunnable = timeout

            main.post {
                if (!continuation.isActive) return@post
                val view = WebView(context.applicationContext)
                webView = view

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(view, true)

                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.databaseEnabled = true
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 15; Mobile) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        main.postDelayed({ inspect(view, 1) }, 1_500L)
                    }

                    override fun onLoadResource(view: WebView, resourceUrl: String) {
                        addCandidate(resourceUrl)
                    }

                    @Deprecated("Deprecated in API 23")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        if (failingUrl == url) finish(fallbackSnapshot())
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) finish(fallbackSnapshot())
                    }
                }

                main.postDelayed(timeout, timeoutMs)
                view.loadUrl(url)
            }

            continuation.invokeOnCancellation {
                main.post { finish(fallbackSnapshot()) }
            }
        }
}
