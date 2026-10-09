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
import org.json.JSONTokener
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
            val instagramShortcode = if (platform == MediaPlatform.Instagram) {
                runCatching {
                    val segments = URI(url).path.orEmpty().split('/').filter(String::isNotBlank)
                    val mediaIndex = segments.indexOfFirst {
                        it.lowercase() in setOf("reel", "reels", "p", "tv")
                    }
                    if (mediaIndex >= 0) {
                        segments.getOrNull(mediaIndex + 1)?.takeIf {
                            it.matches(Regex("[A-Za-z0-9_-]{5,}"))
                        }
                    } else {
                        null
                    }
                }.getOrNull()
            } else {
                null
            }
            var instagramApiStatus: String? = null
            var title: String? = null
            var thumbnail: String? = null
            var durationMs: Long? = null
            var finalUrl: String? = null

            fun normalizeMediaUrl(raw: String): String =
                raw.trim()
                    .replace("\\/", "/")
                    .replace("\\u002F", "/", ignoreCase = true)
                    .replace("\\u0026", "&", ignoreCase = true)
                    .replace("\\u003F", "?", ignoreCase = true)
                    .replace("\\u003D", "=", ignoreCase = true)
                    .replace("\\u003A", ":", ignoreCase = true)

            fun isMedia(raw: String): Boolean {
                val value = normalizeMediaUrl(raw)
                val lower = value.lowercase()
                if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
                val path = lower.substringBefore('?').substringBefore('#')
                val ext = path.substringAfterLast('.', "")
                // Instagram/CDN image URLs can share the same /o1/v/ and /v/t paths
                // as video assets. Never promote a known image extension to a media source.
                if (ext in IMAGE_EXTENSIONS) return false
                if (ext in MEDIA_EXTENSIONS || ext == "m3u8" || ext == "mpd" || "/videoplayback" in lower) {
                    return true
                }
                if (Regex("""[?&](?:mime|content-type|type)=[^&]*?(?:video|audio)""").containsMatchIn(lower)) {
                    return true
                }
                val host = runCatching { URI(value).host.orEmpty().lowercase() }.getOrDefault("")
                return (
                    host.endsWith(".fbcdn.net") ||
                        host.endsWith(".cdninstagram.com") ||
                        host == "cdninstagram.com"
                    ) && (
                    "/o1/v/" in path ||
                        "/v/t" in path ||
                        "/video" in path
                    )
            }

            fun isLikelyPlayableMedia(raw: String): Boolean {
                val value = normalizeMediaUrl(raw)
                val lower = value.lowercase()
                val path = lower.substringBefore('?').substringBefore('#')
                val ext = path.substringAfterLast('.', "")
                if (ext in IMAGE_EXTENSIONS) return false
                if (ext in MEDIA_EXTENSIONS || ext == "m3u8" || ext == "mpd" || "/videoplayback" in lower) return true
                val host = runCatching { URI(value).host.orEmpty().lowercase() }.getOrDefault("")
                return host.endsWith(".fbcdn.net") || host.endsWith(".cdninstagram.com")
                    || host == "cdninstagram.com"
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
                    instagramApiStatus = instagramApiStatus,
                )
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(result)
            }

            fun observe(raw: String?, headers: Map<String, String> = emptyMap()) {
                val value = normalizeMediaUrl(raw.orEmpty())
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
                      const instagramShortcode=__IG_SHORTCODE__;
                      // Ask Instagram's same-origin media endpoint from the WebView itself.
                      // This keeps any existing WebView session cookies inside the WebView;
                      // cookie values are never copied into native state or diagnostic logs.
                      if(instagramShortcode && !window.__ahInstagramApiRequestStarted){
                        window.__ahInstagramApiRequestStarted=true;
                        window.__ahInstagramApiStatus='pending';
                        try{
                          const endpoint=new URL(
                            '/api/v1/media/shortcode/'+encodeURIComponent(instagramShortcode)+'/',
                            location.origin
                          ).toString();
                          fetch(endpoint,{
                            method:'GET',
                            credentials:'include',
                            headers:{
                              'Accept':'application/json, text/plain, */*',
                              'X-IG-App-ID':'936619743392459',
                              'X-Requested-With':'XMLHttpRequest'
                            }
                          }).then(response=>{
                            if(!response.ok){
                              window.__ahInstagramApiStatus='http_'+response.status;
                              return null;
                            }
                            return response.json().then(payload=>({payload}))
                              .catch(()=>{window.__ahInstagramApiStatus='invalid_json';return null;});
                          }).then(result=>{
                            if(!result || !result.payload) return;
                            const discovered=[];
                            const addVideoUrl=raw=>{
                              if(typeof raw!=='string' ||
                                 !(raw.startsWith('https://') || raw.startsWith('http://'))) return;
                              const path=raw.split('?')[0].split('#')[0].toLowerCase();
                              if(['.jpg','.jpeg','.png','.webp','.gif','.avif','.heic','.heif']
                                  .some(ext=>path.endsWith(ext))) return;
                              if(['.mp4','.m4v','.webm','.mov','.m3u8','.mpd']
                                  .some(ext=>path.endsWith(ext)) ||
                                 path.includes('/o1/v/') || path.includes('/v/t')){
                                discovered.push(raw);
                              }
                            };
                            const visit=(node,depth)=>{
                              if(!node || depth>10) return;
                              if(Array.isArray(node)){
                                node.slice(0,100).forEach(item=>visit(item,depth+1));
                                return;
                              }
                              if(typeof node!=='object') return;
                              Object.entries(node).forEach(([key,value])=>{
                                const lower=key.toLowerCase();
                                if(typeof value==='string' &&
                                   ['video_url','playback_url','content_url'].includes(lower)){
                                  addVideoUrl(value);
                                }
                                if(Array.isArray(value) && lower==='video_versions'){
                                  value.forEach(item=>{
                                    if(item && typeof item.url==='string') addVideoUrl(item.url);
                                  });
                                }
                                if(value && typeof value==='object') visit(value,depth+1);
                              });
                            };
                            visit(result.payload,0);
                            window.__ahInstagramApiMedia=[...new Set(discovered)].slice(0,32);
                            const message=String(result.payload.message||'').toLowerCase();
                            window.__ahInstagramApiStatus=
                              (result.payload.require_login===true || message==='login_required')
                                ? 'login_required'
                                : (window.__ahInstagramApiMedia.length ? 'success_media' : 'success_no_media');
                          }).catch(()=>{window.__ahInstagramApiStatus='network_error';});
                        }catch(_){window.__ahInstagramApiStatus='request_error';}
                      }
                      const meta=s=>{const e=document.querySelector(s);return e?e.content:null};
                      // Instagram may defer the actual CDN request until its player starts.
                      // Prime at most two video elements silently so the WebView can observe the
                      // real media request; never click page controls or follow login prompts.
                      const videoNodes=[...document.querySelectorAll('video')].slice(0,2);
                      videoNodes.forEach(v=>{
                        try{
                          v.muted=true;
                          v.playsInline=true;
                          v.setAttribute('playsinline','');
                          if(v.readyState===0) v.load();
                          if(v.paused){
                            const playback=v.play();
                            if(playback&&typeof playback.catch==='function') playback.catch(()=>{});
                          }
                        }catch(_){}
                      });
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
                      const scripts=[...document.scripts].map(s=>s.textContent||'').join('\n');
                      const clean=scripts
                        .replace(/\\u002f/gi,'/')
                        .replace(/\\u0026/gi,'&')
                        .replace(/\\u003f/gi,'?')
                        .replace(/\\u003d/gi,'=')
                        .replace(/\\u003a/gi,':')
                        .replace(/\\\//g,'/');
                      const embedded=[];
                      const keyRe=/"(?:video_url|playback_url|videoUrl|contentUrl|content_url|player_url|stream_url)"\s*:\s*"([^"]+)"/g;
                      let match;
                      while((match=keyRe.exec(clean))!==null) embedded.push(match[1]);
                      const embeddedUrls=(clean.match(/https?:\/\/[^"'<>\\\s]+/g)||[]);
                      const d=[...document.querySelectorAll('video')].map(v=>v.duration).filter(x=>Number.isFinite(x)&&x>0);
                      return JSON.stringify({
                        title:meta('meta[property="og:title"]')||meta('meta[name="twitter:title"]')||document.title||null,
                        thumbnail:meta('meta[property="og:image"]')||meta('meta[name="twitter:image"]')||null,
                        durationSec:d.length?Math.max(...d):null,
                        media:[...new Set([
                          ...elements.map(x=>x.url),
                          ...sources,
                          ...perf,
                          ...embedded,
                          ...embeddedUrls,
                          ...(window.__ahInstagramApiMedia||[])
                        ])].slice(0,120),
                        instagramApiStatus:window.__ahInstagramApiStatus ||
                          (instagramShortcode ? 'pending' : 'not_applicable'),
                        mediaAudio:elements
                      });
                    })();
                """.trimIndent().replace(
                    "__IG_SHORTCODE__",
                    JSONObject.quote(instagramShortcode.orEmpty()),
                )
                view.evaluateJavascript(script) { raw ->
                    runCatching {
                        val decoded = runCatching {
                            JSONTokener(raw).nextValue() as? String ?: raw
                        }.getOrDefault(raw)
                        val json = JSONObject(decoded)
                        val reportedInstagramApiStatus = json.optString("instagramApiStatus")
                            .takeIf { it.isNotBlank() }
                        if (reportedInstagramApiStatus != null &&
                            (reportedInstagramApiStatus != "pending" || instagramApiStatus == null)
                        ) {
                            instagramApiStatus = reportedInstagramApiStatus
                        }
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
                    // Do not terminate on the first discovered URL; Instagram
                    // may expose higher-quality variants a moment later.
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
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
    }
}
