package com.ahdownload.feature.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class AndroidBrowserMediaSessionProvider(
    private val context: Context,
) : BrowserMediaSessionProvider {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var timeout: Runnable? = null
            var finished = false
            var fastFinishScheduled = false
            lateinit var inspect: (WebView) -> Unit
            val mediaUrls = ConcurrentHashMap.newKeySet<String>()
            val requestHeaders = ConcurrentHashMap<String, Map<String, String>>()
            var title: String? = null
            var thumbnail: String? = null
            var durationMs: Long? = null
            var finalUrl: String? = null

            fun isMedia(raw: String): Boolean {
                val lower = raw.lowercase()
                if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
                if (".m3u8" in lower || ".mpd" in lower) return false
                val path = lower.substringBefore('?').substringBefore('#')
                val ext = path.substringAfterLast('.', "")
                val queryMedia = Regex("""[?&](mime|mime_type|content-type|contentType|type|media_type)=(?:video|audio)""")
                    .containsMatchIn(lower)
                val pathHint = Regex("""(?:/videoplayback|/video(?:/|$)|/videos(?:/|$)|/playback(?:/|$)|/stream(?:/|$)|/download(?:/|$))""")
                    .containsMatchIn(lower)
                return ext in MEDIA_EXTENSIONS || queryMedia || pathHint
            }

            fun isLikelyPlayableMedia(raw: String): Boolean {
                val lower = raw.lowercase()
                val path = lower.substringBefore('?').substringBefore('#')
                val ext = path.substringAfterLast('.', "")
                return ext in MEDIA_EXTENSIONS ||
                    "/videoplayback" in lower || "/video/" in lower || "/videos/" in lower
            }

            fun safeHeaders(input: Map<String, String>): Map<String, String> = buildMap {
                input.forEach { (name, value) ->
                    when (name.lowercase()) {
                        "user-agent" -> put("User-Agent", value)
                        "cookie" -> put("Cookie", value)
                        "referer" -> put("Referer", value)
                        "origin" -> put("Origin", value)
                        "accept" -> put("Accept", value)
                        "accept-language" -> put("Accept-Language", value)
                        "sec-fetch-dest" -> put("Sec-Fetch-Dest", value)
                        "sec-fetch-mode" -> put("Sec-Fetch-Mode", value)
                        "sec-fetch-site" -> put("Sec-Fetch-Site", value)
                    }
                }
            }

            fun observe(raw: String?, headers: Map<String, String> = emptyMap()) {
                val value = raw?.trim().orEmpty()
                if (!isMedia(value)) return
                mediaUrls.add(value)
                val safe = safeHeaders(headers).toMutableMap()
                runCatching {
                    CookieManager.getInstance().getCookie(value)
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { cookie ->
                    safe["Cookie"] = cookie
                }
                if (safe.isNotEmpty()) requestHeaders[value] = safe

                if (isLikelyPlayableMedia(value) && !fastFinishScheduled) {
                    fastFinishScheduled = true
                    main.postDelayed({
                        if (finished) return@postDelayed
                        webView?.let { inspect(it) }
                    }, 350L)
                }
            }

            fun finish() {
                if (finished) return
                finished = true
                timeout?.let(main::removeCallbacks)
                finalUrl = webView?.url ?: url
                val result = BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = finalUrl,
                    title = title,
                    thumbnailUrl = thumbnail,
                    durationMs = durationMs,
                    mediaUrls = mediaUrls.toList().take(MAX_MEDIA_URLS),
                    requestHeadersByUrl = requestHeaders.toMap(),
                )
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(result)
            }

            inspect = fun(view: WebView) {
                if (finished) return
                val script = """
                    (function(){
                      const meta=s=>{const e=document.querySelector(s);return e?e.content:null};
                      const media=[...document.querySelectorAll('video,audio,source')]
                        .map(e=>e.currentSrc||e.src||e.getAttribute('data-src')).filter(Boolean);
                      const perf=(performance.getEntriesByType('resource')||[]).map(e=>e.name).filter(Boolean);
                      const d=[...document.querySelectorAll('video')].map(v=>v.duration).filter(x=>Number.isFinite(x)&&x>0);
                      return JSON.stringify({
                        title:meta('meta[property="og:title"]')||meta('meta[name="twitter:title"]')||document.title||null,
                        thumbnail:meta('meta[property="og:image"]')||meta('meta[name="twitter:image"]')||null,
                        durationSec:d.length?Math.max(...d):null,
                        media:[...new Set([...media,...perf])].slice(0,80)
                      });
                    })();
                """.trimIndent()
                view.evaluateJavascript(script) { raw ->
                    runCatching {
                        val json = JSONObject(raw.removeSurrounding(""").replace("\"", """))
                        json.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                        json.optString("thumbnail").takeIf { it.startsWith("http") }?.let { thumbnail = it }
                        json.optDouble("durationSec", -1.0).takeIf { it > 0 }?.let { durationMs = (it * 1000).toLong() }
                        json.optJSONArray("media")?.let { array ->
                            for (i in 0 until array.length()) observe(array.optString(i))
                        }
                    }
                    if (mediaUrls.isNotEmpty()) finish()
                }
            }

            continuation.invokeOnCancellation {
                main.post {
                    finished = true
                    timeout?.let(main::removeCallbacks)
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                }
            }

            main.post {
                val view = WebView(context.applicationContext)
                webView = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = USER_AGENT
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        observe(request.url.toString(), request.requestHeaders)
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onLoadResource(view: WebView, resourceUrl: String) {
                        observe(resourceUrl)
                        super.onLoadResource(view, resourceUrl)
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        finalUrl = pageUrl
                        view.postDelayed({ inspect(view) }, 500L)
                        view.postDelayed({ inspect(view) }, 1600L)
                        view.postDelayed({ inspect(view) }, 3200L)
                        view.postDelayed({ inspect(view) }, 6000L)
                        view.postDelayed({ finish() }, 9000L)
                    }
                }
                timeout = Runnable { finish() }
                main.postDelayed(timeout!!, 16000L)
                view.loadUrl(url)
            }
        }

    private companion object {
        const val MAX_MEDIA_URLS = 96
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
        val MEDIA_EXTENSIONS = setOf("mp4","m4v","webm","mov","mkv","3gp","avi","m4a","mp3","aac","ogg","flac","wav")
    }
}
