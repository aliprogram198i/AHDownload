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
    val mediaUrls: List<String>,
    val authenticated: Boolean = false
)

/**
 * Loads a first-party platform page in Android WebView and captures the
 * authenticated session plus media resource URLs. Cookie values never enter
 * telemetry.
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
            var lastTitle: String? = null
            var authenticated = false
            var inspectionCount = 0

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

            fun cookieSessionPresent(): Boolean {
                val names = currentCookies().orEmpty()
                    .split(';')
                    .mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
                    .toSet()
                val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
                    .removePrefix("www.")
                return when {
                    host == "instagram.com" || host.endsWith(".instagram.com") ->
                        "sessionid" in names
                    host == "facebook.com" || host.endsWith(".facebook.com") ->
                        "c_user" in names && "xs" in names
                    host == "youtube.com" || host.endsWith(".youtube.com") ->
                        setOf("SID", "SAPISID", "APISID").any(names::contains)
                    else -> false
                }
            }

            fun fallbackSnapshot(): WebViewMediaSnapshot =
                WebViewMediaSnapshot(
                    currentCookies(),
                    lastTitle,
                    capturedUrls.take(64),
                    authenticated || cookieSessionPresent()
                )

            fun inspect(view: WebView, attempt: Int = 1) {
                inspectionCount = attempt
                val script = """
                    (function() {
                      const urls = new Set();
                      const add = (v) => {
                        if (!v) return;
                        try { v = new URL(v, location.href).href; } catch (_) {}
                        if (/^https?:///i.test(v)) urls.add(v);
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

                      const path = (location.pathname || '').toLowerCase();
                      const loginPage =
                        /\/accounts\/(?:login|signup|checkpoint)/.test(path) ||
                        /\/login(?:\/|$)/.test(path) ||
                        /checkpoint/.test(path);

                      return JSON.stringify({
                        title: document.title || null,
                        loginPage: loginPage,
                        urls: Array.from(urls).slice(0, 64)
                      });
                    })();
                """.trimIndent()

                view.evaluateJavascript(script) { raw ->
                    val parsed = runCatching {
                        val decoded = org.json.JSONTokener(raw ?: "null").nextValue()
                        if (decoded !is String) return@runCatching null
                        org.json.JSONObject(decoded)
                    }.getOrNull()

                    parsed?.optString("title")?.takeIf { it.isNotBlank() }?.let { lastTitle = it }
                    parsed?.optJSONArray("urls")?.let { array ->
                        for (i in 0 until array.length()) addCandidate(array.optString(i))
                    }

                    val loginPage = parsed?.optBoolean("loginPage", false) ?: false
                    authenticated = !loginPage && cookieSessionPresent()

                    // Do not stop after the first arbitrary network resource.
                    // Instagram often exposes CDN resources before the final
                    // playable URL is attached to <video>.
                    if ((authenticated && capturedUrls.isNotEmpty() && attempt >= 2) || attempt >= 4) {
                        finish(
                            WebViewMediaSnapshot(
                                currentCookies(),
                                lastTitle,
                                capturedUrls.take(64),
                                authenticated
                            )
                        )
                    } else {
                        main.postDelayed({ inspect(view, attempt + 1) }, 1_500L)
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
                    "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        main.postDelayed({ inspect(view, 1) }, 1_000L)
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
