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
    val authenticated: Boolean
)

/**
 * Loads a platform page in Android WebView and captures session state plus
 * media URLs. Cookie values are never logged or exposed to telemetry.
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
            var pageAuthenticated = false
            var pageTitle: String? = null

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

            fun currentCookies(): String? {
                CookieManager.getInstance().flush()
                return CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
            }

            fun cookieAuthenticated(cookies: String?): Boolean {
                val names = cookies.orEmpty()
                    .split(';')
                    .mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
                    .toSet()
                val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
                return when {
                    host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                        setOf("SID", "SAPISID", "APISID", "__Secure-3PSID", "LOGIN_INFO").any(names::contains)
                    host == "instagram.com" || host.endsWith(".instagram.com") ->
                        "sessionid" in names
                    host == "facebook.com" || host.endsWith(".facebook.com") ->
                        "c_user" in names && "xs" in names
                    else -> false
                }
            }

            fun fallbackSnapshot(): WebViewMediaSnapshot =
                WebViewMediaSnapshot(currentCookies(), pageTitle, capturedUrls.take(64), pageAuthenticated || cookieAuthenticated(currentCookies()))

            fun inspect(view: WebView, attempt: Int = 1) {
                val script = """
                    (function() {
                      const urls = new Set();
                      const add = (v) => {
                        if (!v) return;
                        try {
                          v = v.replace(/\\\//g, '/');
                          v = new URL(v, location.href).href;
                        } catch (_) {}
                        if (/^https?:\/\//i.test(v)) urls.add(v);
                      };
                      document.querySelectorAll('video').forEach(v => {
                        add(v.currentSrc);
                        add(v.src);
                        try {
                          v.muted = true;
                          v.setAttribute('muted', '');
                          v.playsInline = true;
                          v.play().catch(() => {});
                        } catch (_) {}
                      });
                      document.querySelectorAll('video source, source').forEach(s => add(s.src));
                      document.querySelectorAll('link[rel="preload"][as="video"], link[as="video"]').forEach(l => add(l.href));
                      document.querySelectorAll('[data-video-url], [data-media-url]').forEach(el => {
                        add(el.getAttribute('data-video-url'));
                        add(el.getAttribute('data-media-url'));
                      });
                      document.querySelectorAll(
                        'meta[property="og:video"], meta[property="og:video:secure_url"], ' +
                        'meta[name="twitter:player:stream"]'
                      ).forEach(m => add(m.content));

                      const html = document.documentElement
                        ? (document.documentElement.outerHTML || '') : '';
                      const escaped = html
                        .replace(/\\\\\//g, '/')
                        .replace(/\\u002F/gi, '/')
                        .replace(/\\u003A/gi, ':')
                        .replace(/\\u0026/gi, '&');
                      const mediaPatterns = [
                        /https?:\/\/[^"'<>\s]+?\.(?:mp4|m4v|webm|mov)(?:[?#][^"'<>\s]*)?/gi,
                        /https?:\/\/[^"'<>\s]*(?:cdninstagram|fbcdn|scontent)[^"'<>\s]*/gi,
                        /"(?:video_url|playback_url|browser_native_hd_url|browser_native_sd_url|contentUrl)"\s*:\s*"([^"]+)"/gi,
                        /"(?:video_versions|video_versions_2)"\s*:\s*\[(.*?)\]/gi,
                        /"src"\s*:\s*"(https?:\\/\\/[^"]+)"/gi
                      ];
                      mediaPatterns.forEach(re => {
                        let match;
                        while ((match = re.exec(escaped)) !== null) add(match[1] || match[0]);
                      });
                      try {
                        const decodedHtml = decodeURIComponent(escaped);
                        if (decodedHtml !== escaped) {
                          mediaPatterns.forEach(re => {
                            let match;
                            while ((match = re.exec(decodedHtml)) !== null) add(match[1] || match[0]);
                          });
                        }
                      } catch (_) {}

                      try {
                        performance.getEntriesByType('resource').forEach(e => {
                          const n = e.name || '';
                          if (/\\.(?:mp4|m4v|webm|mov|m3u8)(?:[?#]|$)/i.test(n) ||
                              /\\/(?:video|playback|stream)(?:[/?]|$)/i.test(n) ||
                              /(cdninstagram|fbcdn|scontent)/i.test(n)) add(n);
                        });
                      } catch (_) {}

                      const bodyText = (document.body && document.body.innerText || '').toLowerCase();
                      const host = location.hostname.toLowerCase();
                      const hasYoutubeAccount =
                        !!document.querySelector('ytd-masthead #avatar-btn, ytd-topbar-menu-button-renderer #avatar-btn') ||
                        (!!document.querySelector('ytd-masthead') && !bodyText.includes('sign in'));
                      const hasInstagramAccount =
                        !!document.querySelector('svg[aria-label="Home"], svg[aria-label="New post"]') &&
                        !bodyText.includes('log in');
                      const hasFacebookAccount =
                        !!document.querySelector('[aria-label*="Account"], [aria-label*="profile" i]') &&
                        !bodyText.includes('log in');
                      const authenticated =
                        (host.includes('youtube.') || host === 'youtu.be') ? hasYoutubeAccount :
                        host.includes('instagram.') ? hasInstagramAccount :
                        host.includes('facebook.') ? hasFacebookAccount : false;

                      return JSON.stringify({
                        title: document.title || null,
                        authenticated,
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

                    parsed?.optString("title")?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                    pageAuthenticated = pageAuthenticated || (parsed?.optBoolean("authenticated", false) == true)
                    parsed?.optJSONArray("urls")?.let { array ->
                        for (i in 0 until array.length()) addCandidate(array.optString(i))
                    }

                    if (attempt >= 8) {
                        val cookies = currentCookies()
                        finish(WebViewMediaSnapshot(cookies, pageTitle, capturedUrls.take(64), pageAuthenticated || cookieAuthenticated(cookies)))
                    } else {
                        main.postDelayed({ inspect(view, attempt + 1) }, 1_000L)
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
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        main.postDelayed({ inspect(view, 1) }, 1_200L)
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

    private fun isLikelyMediaUrl(url: String): Boolean =
        Regex("""(?i)\.(?:mp4|m4v|webm|mov)(?:[?#].*)?$""").containsMatchIn(url) ||
            Regex("""(?i)(cdninstagram|fbcdn|scontent).*(?:video|\.mp4)""").containsMatchIn(url)

}
