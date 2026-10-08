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
            var settleFinish: Runnable? = null
            var finished = false
            var firstMediaObservedAt = 0L
            lateinit var inspect: (WebView) -> Unit
            val mediaUrls = ConcurrentHashMap.newKeySet<String>()
            val requestHeaders = ConcurrentHashMap<String, Map<String, String>>()
            val mediaHasAudioByUrl = ConcurrentHashMap<String, Boolean>()
            var title: String? = null
            var thumbnail: String? = null
            var durationMs: Long? = null
            var finalUrl: String? = null

            fun isMedia(raw: String): Boolean {
                val lower = raw.lowercase()
                if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
                val path = lower.substringBefore('?').substringBefore('#')
                val ext = path.substringAfterLast('.', "")
                return ext in MEDIA_EXTENSIONS ||
                    ext == "m3u8" ||
                    ext == "mpd" ||
                    "/videoplayback" in lower ||
                    Regex("""[?&](mime|content-type|type)=(video|audio)(%2f|/)""").containsMatchIn(lower)
            }

            fun isLikelyPlayableMedia(raw: String): Boolean {
                val lower = raw.lowercase()
                val path = lower.substringBefore('?').substringBefore('#')
                val ext = path.substringAfterLast('.', "")
                return ext in MEDIA_EXTENSIONS || "/videoplayback" in lower
            }

            fun safeHeaders(input: Map<String, String>): Map<String, String> = buildMap {
                input.forEach { (name, value) ->
                    when (name.lowercase()) {
                        "user-agent" -> put("User-Agent", value)
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

            fun finish() {
                if (finished) return
                finished = true
                timeout?.let(main::removeCallbacks)
                settleFinish?.let(main::removeCallbacks)
                finalUrl = webView?.url ?: url
                val result = BrowserMediaSession(
                    platform = platform,
                    pageUrl = url,
                    finalUrl = finalUrl,
                    title = title,
                    thumbnailUrl = thumbnail,
                    durationMs = durationMs,
                    mediaUrls = mediaUrls.toList().take(MAX_MEDIA_URLS),
                    mediaHasAudioByUrl = mediaHasAudioByUrl.toMap(),
                    requestHeadersByUrl = requestHeaders.toMap(),
                )
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(result)
            }

            fun observe(raw: String?, headers: Map<String, String> = emptyMap()) {
                val value = raw?.trim().orEmpty()
                if (!isMedia(value)) return
                mediaUrls.add(value)
                val safe = safeHeaders(headers)
                if (safe.isNotEmpty()) requestHeaders[value] = safe

                if (isLikelyPlayableMedia(value)) {
                    if (firstMediaObservedAt == 0L) {
                        firstMediaObservedAt = System.currentTimeMillis()
                    }
                    webView?.let { view ->
                        view.postDelayed({ inspect(view) }, 250L)
                    }

                    // Keep collecting media after the first playable request until
                    // one fixed deadline. Repeated requests must never extend it.
                    settleFinish?.let(main::removeCallbacks)
                    val deadline = firstMediaObservedAt + MAX_MEDIA_CAPTURE_WINDOW_MS
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                    settleFinish = Runnable { finish() }.also {
                        main.postDelayed(it, remaining)
                    }                }
            }

            inspect = fun(view: WebView) {
                if (finished) return
                val script = """
                    (function(){
                      const meta=s=>{const e=document.querySelector(s);return e?e.content:null};
                      const elements=[...document.querySelectorAll('video,audio')].map(e=>{
                        const url=e.currentSrc||e.src||e.getAttribute('data-src');
                        const tag=e.tagName.toLowerCase();
                        const hasAudio=tag==='audio'||
                          (e.audioTracks&&typeof e.audioTracks.length==='number'&&e.audioTracks.length>0)||
                          e.mozHasAudio===true||
                          (typeof e.webkitAudioDecodedByteCount==='number'&&e.webkitAudioDecodedByteCount>0);
                        return url?{url,hasAudio}:null;
                      }).filter(Boolean);
                      const sources=[...document.querySelectorAll('source')]
                        .map(e=>e.src||e.getAttribute('data-src')).filter(Boolean);
                      const perf=(performance.getEntriesByType('resource')||[]).map(e=>e.name).filter(Boolean);
                      const d=[...document.querySelectorAll('video')].map(v=>v.duration).filter(x=>Number.isFinite(x)&&x>0);
                      return JSON.stringify({
                        title:meta('meta[property="og:title"]')||meta('meta[name="twitter:title"]')||document.title||null,
                        thumbnail:meta('meta[property="og:image"]')||meta('meta[name="twitter:image"]')||null,
                        durationSec:d.length?Math.max(...d):null,
                        media:[...new Set([...elements.map(x=>x.url),...sources,...perf])].slice(0,80),
                        mediaAudio:elements
                      });
                    })();
                """.trimIndent()
                view.evaluateJavascript(script) { raw ->
                    runCatching {
                        val json = JSONObject(raw.removeSurrounding(""").replace("\"", """))
                        json.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                        json.optString("thumbnail").takeIf { it.startsWith("http") }?.let { thumbnail = it }
                        json.optDouble("durationSec", -1.0).takeIf { it > 0 }?.let { durationMs = (it * 1000).toLong() }
                        json.optJSONArray("mediaAudio")?.let { array ->
                            for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                val mediaUrl = item.optString("url").trim()
                                if (mediaUrl.isBlank()) continue
                                observe(mediaUrl)
                                mediaHasAudioByUrl[mediaUrl] = item.optBoolean("hasAudio", false)
                            }
                        }
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

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        finalUrl = pageUrl
                        view.postDelayed({ inspect(view) }, 450L)
                        view.postDelayed({ inspect(view) }, 1400L)
                        view.postDelayed({ inspect(view) }, 2600L)
                        view.postDelayed({ inspect(view) }, 4200L)
                        view.postDelayed({
                            if (mediaUrls.isNotEmpty()) finish()
                        }, 6200L)
                    }
                }
                timeout = Runnable { finish() }
                main.postDelayed(timeout!!, 10000L)
                view.loadUrl(url)
            }
        }

    private companion object {
        const val MAX_MEDIA_URLS = 64
        const val MAX_MEDIA_CAPTURE_WINDOW_MS = 5200L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
        val MEDIA_EXTENSIONS = setOf("mp4","m4v","webm","mov","mkv","3gp","avi","m4a","mp3","aac","ogg","flac","wav")
    }
}
