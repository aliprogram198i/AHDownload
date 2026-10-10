package com.ahdownload.feature.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ahdownload.domain.resolver.youtube.YouTubeSessionProvider
import com.ahdownload.domain.resolver.youtube.YouTubeSessionSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal fun shouldLoadYouTubeEmbeddedFallback(
    attempt: Int,
    observedMediaCount: Int,
    hasPlayerResponse: Boolean,
    embeddedFallbackLoaded: Boolean,
): Boolean =
    attempt == 5 &&
        !embeddedFallbackLoaded &&
        (observedMediaCount == 0 || !hasPlayerResponse)

class AndroidYouTubeSessionProvider(private val context: Context) : YouTubeSessionProvider {
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun snapshot(url: String): YouTubeSessionSnapshot =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var timeout: Runnable? = null
            val videos = ConcurrentHashMap.newKeySet<String>()
            val audios = ConcurrentHashMap.newKeySet<String>()
            val browserRequestHeaders = ConcurrentHashMap<String, Map<String, String>>()
            val observedGoogleVideoUrls = ConcurrentHashMap.newKeySet<String>()
            val browserPoTokenObserved = AtomicBoolean(false)
            var browserPoToken: String? = null
            var playerResponse: String? = null
            var authenticated = false
            var embeddedFallbackLoaded = false

            fun add(set: MutableSet<String>, raw: String?) {
                val value = raw?.trim().orEmpty()
                if ((value.startsWith("https://") || value.startsWith("http://")) &&
                    !value.contains(".m3u8", ignoreCase = true)
                ) {
                    set.add(value)
                }
            }

            fun cookies(): String? {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                manager.flush()
                return manager.getCookie("https://www.youtube.com/")?.takeIf { it.isNotBlank() }
            }

            fun cookieAuth(value: String?): Boolean {
                val names = value.orEmpty().split(';')
                    .mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
                    .toSet()
                return setOf(
                    "SID",
                    "SAPISID",
                    "APISID",
                    "__Secure-3PSID",
                    "LOGIN_INFO",
                ).any(names::contains)
            }

            fun extractPoToken(resourceUrl: String): String? =
                runCatching {
                    java.net.URI(resourceUrl).rawQuery.orEmpty()
                        .split('&')
                        .firstNotNullOfOrNull { part ->
                            val pieces = part.split('=', limit = 2)
                            if (pieces.size != 2) return@firstNotNullOfOrNull null
                            val name = pieces[0].lowercase()
                            if (name != "pot" && name != "potc" && !name.contains("po_token")) {
                                return@firstNotNullOfOrNull null
                            }
                            java.net.URLDecoder.decode(pieces[1], "UTF-8")
                                .takeIf { it.isNotBlank() }
                        }
                }.getOrNull()

            fun hasPoToken(resourceUrl: String): Boolean =
                runCatching {
                    java.net.URI(resourceUrl).rawQuery.orEmpty()
                        .split('&')
                        .mapNotNull { part ->
                            part.substringBefore('=').lowercase().takeIf { it.isNotBlank() }
                        }
                        .any { it == "pot" || it == "potc" || it.contains("po_token") }
                }.getOrDefault(false)

            fun hasDirectMediaHint(resourceUrl: String): Boolean =
                runCatching {
                    val query = java.net.URI(resourceUrl).rawQuery.orEmpty()
                    val parameters = query.split('&').mapNotNull { part ->
                        val pieces = part.split('=', limit = 2)
                        if (pieces.size != 2) null else pieces[0] to pieces[1]
                    }
                    val hasItag = parameters.any { (name, value) ->
                        name.equals("itag", ignoreCase = true) &&
                            java.net.URLDecoder.decode(value, "UTF-8").toIntOrNull() != null
                    }
                    val mime = parameters.firstOrNull { (name, _) ->
                        name.equals("mime", ignoreCase = true) || name.equals("type", ignoreCase = true)
                    }?.second?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        ?.lowercase()
                        .orEmpty()
                    hasItag || mime.startsWith("video/") || mime.startsWith("audio/")
                }.getOrDefault(false)

            fun safeBrowserHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
                headers.forEach { (name, value) ->
                    when (name.lowercase()) {
                        "user-agent" -> put("User-Agent", value)
                        "referer" -> put("Referer", value)
                        "origin" -> put("Origin", value)
                        "accept" -> put("Accept", value)
                        "accept-language" -> put("Accept-Language", value)
                        "sec-fetch-dest" -> put("Sec-Fetch-Dest", value)
                        "sec-fetch-mode" -> put("Sec-Fetch-Mode", value)
                        "sec-fetch-site" -> put("Sec-Fetch-Site", value)
                        "sec-ch-ua" -> put("Sec-CH-UA", value)
                        "sec-ch-ua-mobile" -> put("Sec-CH-UA-Mobile", value)
                        "sec-ch-ua-platform" -> put("Sec-CH-UA-Platform", value)
                        "x-goog-visitor-id" -> put("X-Goog-Visitor-Id", value)
                        "x-youtube-client-name" -> put("X-YouTube-Client-Name", value)
                        "x-youtube-client-version" -> put("X-YouTube-Client-Version", value)
                        "range" -> put("Range", value)
                    }
                }
            }

            fun captureBrowserMedia(resourceUrl: String, requestHeaders: Map<String, String> = emptyMap()) {
                val lower = resourceUrl.lowercase()
                if (!lower.startsWith("https://") && !lower.startsWith("http://")) return
                if (lower.contains(".m3u8")) return
                if (!runCatching {
                        java.net.URI(resourceUrl).host?.lowercase()?.endsWith(".googlevideo.com") == true
                    }.getOrDefault(false)
                ) return
                if (!lower.contains("/videoplayback")) return

                // Count all observed GoogleVideo requests for diagnostics, but do not
                // classify unmarked protocol/SABR requests as raw video or audio.
                observedGoogleVideoUrls.add(resourceUrl)
                if (!hasDirectMediaHint(resourceUrl)) return
                extractPoToken(resourceUrl)?.let {
                    browserPoToken = it
                    browserPoTokenObserved.set(true)
                }

                val safeHeaders = buildMap {
                    putAll(safeBrowserHeaders(requestHeaders))
                    CookieManager.getInstance().getCookie(resourceUrl)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("Cookie", it) }
                }
                if (safeHeaders.isNotEmpty()) {
                    browserRequestHeaders[resourceUrl] = safeHeaders
                }

                when {
                    Regex("""[?&](?:mime|type)=audio(?:%2f|/)""").containsMatchIn(lower) ->
                        audios.add(resourceUrl)
                    Regex("""[?&](?:mime|type)=video(?:%2f|/)""").containsMatchIn(lower) ->
                        videos.add(resourceUrl)
                    else ->
                        videos.add(resourceUrl)
                }
            }

            fun finish() {
                if (finished) return
                finished = true
                timeout?.let(main::removeCallbacks)
                val userAgent = webView?.settings?.userAgentString
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) {
                    val c = cookies()
                    continuation.resume(
                        YouTubeSessionSnapshot(
                            cookies = c,
                            videoUrls = videos.take(24),
                            audioUrls = audios.take(24),
                            playerResponse = playerResponse,
                            authenticated = authenticated || cookieAuth(c),
                            userAgent = userAgent,
                            browserRequestHeaders = browserRequestHeaders.toMap(),
                            browserMediaObservedCount = observedGoogleVideoUrls.size,
                            browserPoTokenObserved = browserPoTokenObserved.get(),
                            browserPoToken = browserPoToken,
                        ),
                    )
                }
            }

            fun inspect(view: WebView, attempt: Int) {
                if (finished) return
                if (
                    shouldLoadYouTubeEmbeddedFallback(
                        attempt = attempt,
                        observedMediaCount = observedGoogleVideoUrls.size,
                        hasPlayerResponse = !playerResponse.isNullOrBlank(),
                        embeddedFallbackLoaded = embeddedFallbackLoaded,
                    )
                ) {
                    val videoId = runCatching {
                        val uri = java.net.URI(url)
                        val host = uri.host?.lowercase().orEmpty()
                        when {
                            host == "youtu.be" -> uri.path.trim('/').substringBefore('/').takeIf { it.isNotBlank() }
                            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                                val queryId = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                                    val pieces = part.split('=', limit = 2)
                                    if (pieces.size == 2 && pieces[0] == "v") pieces[1] else null
                                }
                                queryId ?: uri.path.trim('/').split('/').let { parts ->
                                    val index = parts.indexOfFirst { it == "shorts" || it == "embed" || it == "live" }
                                    parts.getOrNull(index + 1)
                                }
                            }
                            else -> null
                        }
                    }.getOrNull()
                    if (videoId != null) {
                        embeddedFallbackLoaded = true
                        view.loadUrl("https://www.youtube.com/embed/$videoId?html5=1&autoplay=1&playsinline=1")
                        return
                    }
                }
                val script = """(function(){
                    const v=new Set(),a=new Set();

                    const isHttp=x=>/^https?:\/\//i.test(x);
                    const isM3u8=x=>/.m3u8(?:[?#]|$)/i.test(x);
                    const isGoogleVideo=x=>{try{return new URL(x).hostname.toLowerCase().endsWith(".googlevideo.com")}catch(_){return false}};
                    const hasDirectMediaHint=x=>{try{const u=new URL(x),itag=u.searchParams.get('itag')||'',mime=(u.searchParams.get('mime')||u.searchParams.get('type')||'').toLowerCase();return /^\d+$/.test(itag)||/^video\//.test(mime)||/^audio\//.test(mime)}catch(_){return false}};
                    const classify=x=>{try{const q=new URL(x).search.toLowerCase();if(q.includes("mime=audio%2f")||q.includes("mime=audio/")||q.includes("type=audio%2f")||q.includes("type=audio/"))return a;if(q.includes("mime=video%2f")||q.includes("mime=video/")||q.includes("type=video%2f")||q.includes("type=video/"))return v}catch(_){}return null};
                    const addResource=x=>{if(!isHttp(x)||isM3u8(x))return;const target=classify(x);if(target&&hasDirectMediaHint(x))target.add(x);else if(!target&&isGoogleVideo(x)&&/\/videoplayback(?:[/?]|$)/i.test(x)&&hasDirectMediaHint(x))v.add(x)};
                    const add=(s,x)=>{if(!x)return;try{x=new URL(x,location.href).href}catch(_){} 
                      if(!/^https?:\/\//i.test(x)||/.m3u8(?:[?#]|$)/i.test(x))return;
                      if(isGoogleVideo(x)&&!hasDirectMediaHint(x))return;
                      s.add(x)};
                    document.querySelectorAll('video').forEach(e=>{
                      add(v,e.currentSrc);add(v,e.src);
                      e.querySelectorAll('source').forEach(s=>add(v,s.src));
                      try{e.muted=true;e.playsInline=true;e.play().catch(()=>{})}catch(_){}
                    });
                    document.querySelectorAll('audio').forEach(e=>{
                      add(a,e.currentSrc);add(a,e.src);
                      e.querySelectorAll('source').forEach(s=>add(a,s.src))
                    });
                    try{performance.getEntriesByType('resource').forEach(e=>{
                      const u=e.name||'',l=u.toLowerCase();
                      if(!/^https?:\/\//i.test(u)||/.m3u8(?:[?#]|$)/i.test(u))return;
                      addResource(u)
                    })}catch(_){}
                    let p=null;
                    try{
                      if(window.ytInitialPlayerResponse)p=JSON.stringify(window.ytInitialPlayerResponse);
                      else if(window.ytplayer&&window.ytplayer.config&&window.ytplayer.config.args&&window.ytplayer.config.args.player_response)
                        p=window.ytplayer.config.args.player_response;
                    }catch(_){}
                    try{
                      const play=document.querySelector('.ytp-play-button,#movie_player .ytp-play-button');
                      if(play) play.click();
                      document.querySelectorAll('video').forEach(e=>{
                        e.muted=true;e.playsInline=true;
                        const promise=e.play();
                        if(promise&&promise.catch) promise.catch(()=>{});
                      });
                    }catch(_){}
                    const t=(document.body&&document.body.innerText||'').toLowerCase();
                    const auth=!!document.querySelector('ytd-masthead #avatar-btn,ytd-topbar-menu-button-renderer #avatar-btn')&&!t.includes('sign in');
                    return JSON.stringify({v:Array.from(v).slice(0,24),a:Array.from(a).slice(0,24),auth,p});
                })();"""

                view.evaluateJavascript(script) { raw ->
                    val parsed = runCatching {
                        val decoded = JSONTokener(raw ?: "null").nextValue()
                        if (decoded is String) org.json.JSONObject(decoded) else null
                    }.getOrNull()

                    authenticated = authenticated || (parsed?.optBoolean("auth", false) == true)
                    parsed?.optString("p")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { playerResponse = it }

                    parsed?.optJSONArray("v")?.let { array ->
                        for (i in 0 until array.length()) add(videos, array.optString(i))
                    }
                    parsed?.optJSONArray("a")?.let { array ->
                        for (i in 0 until array.length()) add(audios, array.optString(i))
                    }

                    if (attempt >= 12) {
                        finish()
                    } else {
                        main.postDelayed({ inspect(view, attempt + 1) }, 1000)
                    }
                }
            }

            main.post {
                if (!continuation.isActive) return@post
                val view = WebView(context.applicationContext)
                webView = view
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(view, true)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.databaseEnabled = true
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.settings.userAgentString = WebSettings.getDefaultUserAgent(context.applicationContext)

                fun isYouTubeGoogleVideo(resourceUrl: String): Boolean =
                    runCatching { java.net.URI(resourceUrl).host?.lowercase()?.endsWith(".googlevideo.com") == true }
                        .getOrDefault(false)

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        CookieManager.getInstance().flush()
                        main.postDelayed({ inspect(view, 1) }, 1500)
                    }

                    @Deprecated("Deprecated in API 23")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        // Keep the session alive; an embedded playback fallback may still succeed.
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        // Keep the session alive; an embedded playback fallback may still succeed.
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        captureBrowserMedia(request.url.toString(), request.requestHeaders)
                        return null
                    }

                    override fun onLoadResource(view: WebView, resourceUrl: String) {
                        if (isYouTubeGoogleVideo(resourceUrl)) {
                            captureBrowserMedia(resourceUrl)
                        }
                    }
                }

                timeout = Runnable { finish() }
                main.postDelayed(timeout, 25_000)
                view.loadUrl(url)
            }

            continuation.invokeOnCancellation {
                main.post { finish() }
            }
        }
}
