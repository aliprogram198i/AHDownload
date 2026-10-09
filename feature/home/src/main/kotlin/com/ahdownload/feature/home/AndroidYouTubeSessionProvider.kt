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

/**
 * Only classify a captured Google Video Server URL when it carries media identity:
 * an explicit MIME/type or a recognized media itag. Bare /videoplayback requests
 * can be partial/SABR traffic and must not be mislabeled as video downloads.
 */
internal fun classifyYouTubeObservedMediaUrl(rawUrl: String): String? {
    val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return null
    if (!uri.path.orEmpty().contains("/videoplayback")) return null

    val query = uri.rawQuery.orEmpty().split('&').mapNotNull { component ->
        val separator = component.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = runCatching {
            java.net.URLDecoder.decode(component.substring(0, separator), "UTF-8")
        }.getOrDefault(component.substring(0, separator)).lowercase()
        val value = runCatching {
            java.net.URLDecoder.decode(component.substring(separator + 1), "UTF-8")
        }.getOrDefault(component.substring(separator + 1))
        key to value
    }.toMap()

    val mime = (query["mime"] ?: query["type"]).orEmpty().lowercase()
    when {
        mime.startsWith("audio/") -> return "audio"
        mime.startsWith("video/") -> return "video"
    }

    val itag = query["itag"]
    return when {
        itag in YOUTUBE_AUDIO_ITAGS -> "audio"
        !itag.isNullOrBlank() -> "video"
        else -> null
    }
}

private val YOUTUBE_AUDIO_ITAGS = setOf(
    "139", "140", "141", "171", "172",
    "249", "250", "251", "256", "258",
    "325", "328", "599", "600",
)

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

                observedGoogleVideoUrls.add(resourceUrl)
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

                when (classifyYouTubeObservedMediaUrl(resourceUrl)) {
                    "audio" -> audios.add(resourceUrl)
                    "video" -> videos.add(resourceUrl)
                    // Keep the observation/header evidence for diagnostics, but never
                    // promote a URL with neither MIME nor itag into a download candidate.
                    else -> Unit
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
                    const audioItags=new Set(["139","140","141","171","172","249","250","251","256","258","325","328","599","600"]);
                    const classify=x=>{try{
                      const u=new URL(x),mime=(u.searchParams.get("mime")||u.searchParams.get("type")||"").toLowerCase();
                      if(mime.startsWith("audio/"))return a;
                      if(mime.startsWith("video/"))return v;
                      if(/\/videoplayback(?:[/?]|$)/i.test(u.pathname)){
                        const itag=u.searchParams.get("itag");
                        if(itag)return audioItags.has(itag)?a:v;
                      }
                    }catch(_){}return null};
                    const addResource=x=>{if(!isHttp(x)||isM3u8(x))return;const target=classify(x);if(target)target.add(x)};
                    const add=(s,x)=>{if(!x)return;try{x=new URL(x,location.href).href}catch(_){} 
                      if(!/^https?:\/\//i.test(x)||/.m3u8(?:[?#]|$)/i.test(x))return;
                      if(isGoogleVideo(x)&&/\/videoplayback(?:[/?]|$)/i.test(x)){const target=classify(x);if(target)target.add(x);return;}
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
                    const responseString=value=>{
                      if(!value)return null;
                      if(typeof value==="string"){
                        try{const parsed=JSON.parse(value);if(parsed&&typeof parsed==="object")value=parsed;else return null}catch(_){return null}
                      }
                      if(value&&typeof value==="object"&&value.playerResponse&&typeof value.playerResponse==="object"&&!value.streamingData)
                        value=value.playerResponse;
                      if(value&&typeof value==="object"&&(value.streamingData||value.videoDetails||value.playabilityStatus))
                        return JSON.stringify(value);
                      return null;
                    };
                    let p=null;
                    try{
                      const moviePlayer=document.querySelector("#movie_player")||window.movie_player;
                      const responseCandidates=[
                        window.ytInitialPlayerResponse,
                        window.ytplayer&&window.ytplayer.config&&window.ytplayer.config.args&&window.ytplayer.config.args.player_response,
                        moviePlayer&&typeof moviePlayer.getPlayerResponse==="function"?moviePlayer.getPlayerResponse():null,
                        window.yt&&window.yt.player&&typeof window.yt.player.getPlayerResponse==="function"?window.yt.player.getPlayerResponse():null,
                        document.querySelector("ytd-player")&&document.querySelector("ytd-player").playerResponse,
                        document.querySelector("ytd-watch-flexy")&&document.querySelector("ytd-watch-flexy").playerResponse
                      ];
                      for(const candidate of responseCandidates){p=responseString(candidate);if(p)break;}
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
