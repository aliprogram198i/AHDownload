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
            var inspectionAttemptCount = 0
            var inspectionCallbackCount = 0
            var inspectionSerial = 0
            var activeInspectionSerial = 0
            var inspectionInFlight = false
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

            fun scheduleInspection(view: WebView, delayMs: Long) {
                // This resolver WebView is intentionally unattached; View.postDelayed may queue work
                // until attachment. Use the main Handler so inspection runs in the background session.
                main.postDelayed({
                    if (!finished && webView === view) inspect(view)
                }, delayMs)
            }

            fun finish() {
                if (finished) return
                finished = true
                timeout?.let(main::removeCallbacks)
                settleFinish?.let(main::removeCallbacks)
                finalUrl = webView?.url ?: url
                if (platform == MediaPlatform.Instagram && instagramApiStatus == null) {
                    instagramApiStatus = when {
                        inspectionAttemptCount == 0 -> "page_not_inspected"
                        inspectionCallbackCount == 0 -> "inspection_callback_missing"
                        else -> "inspection_status_unavailable"
                    }
                }
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
                    inspectionAttemptCount = inspectionAttemptCount,
                    inspectionCallbackCount = inspectionCallbackCount,
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
                        scheduleInspection(view, 250L)
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
                if (finished || inspectionInFlight) return
                inspectionInFlight = true
                val inspectionId = ++inspectionSerial
                activeInspectionSerial = inspectionId
                inspectionAttemptCount++
                if (
                    platform == MediaPlatform.Instagram &&
                    instagramApiStatus in setOf(
                        null,
                        "inspection_callback_timeout",
                        "script_no_result",
                        "script_result_parse_error",
                    )
                ) {
                    instagramApiStatus = "inspection_started"
                }
                main.postDelayed({
                    if (!finished && activeInspectionSerial == inspectionId && inspectionInFlight) {
                        inspectionInFlight = false
                        if (
                            platform == MediaPlatform.Instagram &&
                            instagramApiStatus in setOf(null, "inspection_started", "pending")
                        ) {
                            instagramApiStatus = "inspection_callback_timeout"
                        }
                    }
                }, 1800L)
                val script = """
                    (function(){
                      const instagramShortcode=__IG_SHORTCODE__;
                      // Keep credentials in WebView. Try the lightweight endpoint first,
                      // then the current GraphQL path used by the Instagram web extractor.
                      if(instagramShortcode && !window.__ahInstagramApiRequestStarted){
                        window.__ahInstagramApiRequestStarted=true;
                        window.__ahInstagramApiStatus='pending';
                        const collectMedia=payload=>{
                          const found=[];
                          const addVideoUrl=raw=>{
                            if(typeof raw!=='string' ||
                               !(raw.startsWith('https://') || raw.startsWith('http://'))) return;
                            const path=raw.split('?')[0].split('#')[0].toLowerCase();
                            if(['.jpg','.jpeg','.png','.webp','.gif','.avif','.heic','.heif']
                                .some(ext=>path.endsWith(ext))) return;
                            if(['.mp4','.m4v','.webm','.mov','.m3u8','.mpd']
                                .some(ext=>path.endsWith(ext)) ||
                               path.includes('/o1/v/') || path.includes('/v/t')){
                              found.push(raw);
                            }
                          };
                          const visit=(node,depth)=>{
                            if(!node || depth>32) return;
                            if(Array.isArray(node)){
                              node.slice(0,150).forEach(item=>visit(item,depth+1));
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
                          visit(payload,0);
                          return [...new Set(found)].slice(0,32);
                        };
                        (async()=>{
                          let legacyStatus='not_attempted';
                          try{
                            const endpoint=new URL(
                              '/api/v1/media/shortcode/'+encodeURIComponent(instagramShortcode)+'/',
                              location.origin
                            ).toString();
                            const response=await fetch(endpoint,{
                              method:'GET',
                              credentials:'include',
                              headers:{
                                'Accept':'application/json, text/plain, */*',
                                'X-IG-App-ID':'936619743392459',
                                'X-Requested-With':'XMLHttpRequest'
                              }
                            });
                            if(response.ok){
                              const payload=await response.json();
                              const found=collectMedia(payload);
                              if(found.length){
                                window.__ahInstagramApiMedia=found;
                                window.__ahInstagramApiStatus='success_media';
                                return;
                              }
                              legacyStatus='success_no_media';
                            }else{
                              legacyStatus='http_'+response.status;
                            }
                          }catch(_){legacyStatus='network_error';}
                          try{
                            const markup=document.documentElement
                              ?(document.documentElement.innerHTML||''):'';
                            const extractLsd=source=>{
                              const eqmc=source.match(/<script\b[^>]*\bid=["']__eqmc["'][^>]*>([\s\S]*?)<\/script>/i);
                              if(eqmc&&eqmc[1]){
                                try{
                                  const parsedEqmc=JSON.parse(eqmc[1]);
                                  if(typeof parsedEqmc.l==='string'&&parsedEqmc.l)return parsedEqmc.l;
                                }catch(_){}
                              }
                              const tokenMatch=source.match(/\["LSD",\[\],\{"token":"([^"]+)"/);
                              return tokenMatch?tokenMatch[1]:'';
                            };
                            let lsd=extractLsd(markup);
                            let lsdWarmupStatus='not_needed';
                            // Some public permalink responses do not include __eqmc/LSD. Fetch the
                            // public homepage once in the same WebView session to obtain the fresh
                            // token instead of abandoning the current post immediately.
                            if(!lsd){
                              try{
                                const homeResponse=await fetch('/',{
                                  method:'GET',
                                  credentials:'include',
                                  headers:{
                                    'Accept':'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                                    'X-IG-App-ID':'936619743392459'
                                  }
                                });
                                if(homeResponse.ok){
                                  const homeMarkup=await homeResponse.text();
                                  requestMarkup=homeMarkup+'\n'+markup;
                                  lsd=extractLsd(homeMarkup);
                                  lsdWarmupStatus=lsd?'token_found':'token_absent';
                                }else{
                                  lsdWarmupStatus='http_'+homeResponse.status;
                                }
                              }catch(_){
                                lsdWarmupStatus='network_error';
                              }
                            }
                            // LSD is optional for PolarisPostRootQuery. Some public/logged-out
                            // responses omit it; do not abort source discovery solely because
                            // the page omitted this web-app token.
                            if(!lsd){
                              window.__ahInstagramApiStatus='graphql_lsd_unavailable_home_'+lsdWarmupStatus;
                            }
                            let csrf='';
                            try{
                              const csrfMatch=(document.cookie||'').match(/(?:^|;\s*)csrftoken=([^;]+)/);
                              csrf=csrfMatch?decodeURIComponent(csrfMatch[1]):'';
                            }catch(_){}
                            const variables={
                              shortcode:instagramShortcode,
                              __relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider:false
                            };
                            const form=new URLSearchParams();
                            form.set('doc_id','27128499623469141');
                            form.set('variables',JSON.stringify(variables));
                            if(lsd){
                              form.set('lsd',lsd);
                              const jazoest='2'+Array.from(lsd).reduce((sum,ch)=>sum+ch.charCodeAt(0),0);
                              form.set('jazoest',jazoest);
                            }
                            const graphqlHeaders={
                              'Accept':'*/*',
                              'Content-Type':'application/x-www-form-urlencoded',
                              'X-IG-App-ID':'936619743392459',
                              'X-Requested-With':'XMLHttpRequest',
                              'Origin':location.origin,
                              'Referer':location.origin+'/'
                            };
                            if(csrf)graphqlHeaders['X-CSRFToken']=csrf;
                            if(lsd)graphqlHeaders['X-FB-LSD']=lsd;
                            const response=await fetch('/graphql/query',{
                              method:'POST',
                              credentials:'include',
                              headers:graphqlHeaders,
                              body:form.toString()
                            });
                            const responseText=await response.text();
                            if(!response.ok){
                              window.__ahInstagramApiStatus='legacy_'+legacyStatus+'_graphql_http_'+response.status;
                              return;
                            }
                            let payload=null;
                            try{
                              const cleaned=responseText.trim().replace(/^for\s*\(\s*;;\s*\)\s*;\s*/,'');
                              payload=JSON.parse(cleaned);
                            }catch(_){}
                            const found=payload?collectMedia(payload):[];
                            if(found.length){
                              window.__ahInstagramApiMedia=found;
                              window.__ahInstagramApiStatus='success_media';
                            }else{
                              const lowerResponse=responseText.slice(0,800000).toLowerCase();
                              const responseHasLogin=/login_required|require_login|accounts\/login/.test(lowerResponse);
                              const responseHasGraphqlError=Boolean(payload&&Array.isArray(payload.errors)&&payload.errors.length);
                              const responseIsJson=Boolean(payload);
                              // Some logged-out Instagram pages return useful relay JSON inside
                              // data-sjs scripts instead of the GraphQL response body.
                              const relayScripts=[...document.querySelectorAll('script[type="application/json"][data-sjs]')].slice(0,100);
                              const relayMedia=[];
                              for(const script of relayScripts){
                                try{
                                  const relayPayload=JSON.parse(script.textContent||'');
                                  relayMedia.push(...collectMedia(relayPayload));
                                  if(relayMedia.length>=32)break;
                                }catch(_){}
                              }
                              const uniqueRelay=[...new Set(relayMedia)].slice(0,32);
                              if(uniqueRelay.length){
                                window.__ahInstagramApiMedia=uniqueRelay;
                                window.__ahInstagramApiStatus='success_media_sjs';
                              }else{
                                window.__ahInstagramApiStatus=
                                  responseHasLogin?'login_required':
                                  responseHasGraphqlError?'graphql_rejected':
                                  responseIsJson?'graphql_no_media':'graphql_non_json';
                              }
                            }                          }catch(_){window.__ahInstagramApiStatus='legacy_'+legacyStatus+'_graphql_error';}
                        })();
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
                      const isMediaCandidate=raw=>{
                        if(typeof raw!=='string'||!/^https?:\/\//i.test(raw))return false;
                        try{
                          const u=new URL(raw,location.href);
                          const path=u.pathname.toLowerCase();
                          if(/\.(?:jpe?g|png|webp|gif|avif|heic|heif)$/.test(path))return false;
                          if(/\.(?:mp4|m4v|webm|mov|mkv|3gp|avi|m3u8|mpd|m4a|mp3|aac|ogg|flac|wav)$/.test(path))return true;
                          const host=u.hostname.toLowerCase();
                          return (host.endsWith('.fbcdn.net')||host.endsWith('.cdninstagram.com')||host==='cdninstagram.com')&&
                            /\/(?:o1\/v\/|v\/t|video)/.test(path);
                        }catch(_){return false;}
                      };
                      // Filter by media shape before limiting: Instagram often loads many JS/image resources first.
                      const perf=(performance.getEntriesByType('resource')||[])
                        .map(e=>e.name).filter(isMediaCandidate).slice(0,80);
                      const embedded=[];
                      if(document.readyState==='complete'&&!window.__ahInstagramInlineScanDone){
                        window.__ahInstagramInlineScanDone=true;
                        const scripts=[...document.scripts].slice(0,100)
                          .map(s=>(s.textContent||'').slice(0,100000)).join('\n').slice(0,900000);
                        // Parse structured Instagram data-sjs blobs first: URLs may be nested far
                        // below xig_polaris_media and need JSON-aware traversal, not a shallow regex.
                        const structuredMedia=[];
                        const dataSjs=[...document.querySelectorAll('script[type="application/json"][data-sjs]')].slice(0,100);
                        dataSjs.forEach(script=>{
                          try{structuredMedia.push(...collectMedia(JSON.parse(script.textContent||'')));}
                          catch(_){}
                        });
                        embedded.push(...structuredMedia);
                        const clean=scripts
                          .replace(/\\u002f/gi,'/')
                          .replace(/\\u0026/gi,'&')
                          .replace(/\\u003f/gi,'?')
                          .replace(/\\u003d/gi,'=')
                          .replace(/\\u003a/gi,':')
                          .replace(/\\\//g,'/');
                        const keyRe=/"(?:video_url|playback_url|videoUrl|contentUrl|content_url|player_url|stream_url)"\s*:\s*"([^"]+)"/g;
                        let mediaMatch;
                        while((mediaMatch=keyRe.exec(clean))!==null){
                          const candidate=mediaMatch[1];
                          if(isMediaCandidate(candidate))embedded.push(candidate);
                        }
                        const embeddedUrls=(clean.match(/https?:\/\/[^"'<>\\\s]+/g)||[])
                          .filter(isMediaCandidate).slice(0,80);
                        embedded.push(...embeddedUrls);
                      }
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
                          ...(window.__ahInstagramApiMedia||[])
                        ])].filter(isMediaCandidate).slice(0,64),
                        instagramApiStatus:window.__ahInstagramApiStatus ||
                          (instagramShortcode ? 'pending' : 'not_applicable'),
                        mediaAudio:elements
                      });
                    })();
                """.trimIndent()
                    .replace("__IG_SHORTCODE__", JSONObject.quote(instagramShortcode.orEmpty()))
                view.evaluateJavascript(script) { raw ->
                    inspectionCallbackCount++
                    if (activeInspectionSerial == inspectionId) inspectionInFlight = false
                    if (inspectionId != activeInspectionSerial || finished) return@evaluateJavascript
                    if (raw.isNullOrBlank() || raw == "null") {
                        if (
                            platform == MediaPlatform.Instagram &&
                            instagramApiStatus != "success_media" &&
                            instagramApiStatus != "login_required"
                        ) {
                            instagramApiStatus = "script_no_result"
                        }
                        return@evaluateJavascript
                    }
                    runCatching {
                        val decoded = runCatching {
                            JSONTokener(raw).nextValue() as? String ?: raw
                        }.getOrDefault(raw)
                        val json = JSONObject(decoded)
                        val reportedInstagramApiStatus = json.optString("instagramApiStatus")
                            .takeIf { it.isNotBlank() }
                        if (reportedInstagramApiStatus != null &&
                            (
                                reportedInstagramApiStatus != "pending" ||
                                    instagramApiStatus in setOf(
                                        null,
                                        "inspection_started",
                                        "inspection_callback_timeout",
                                        "script_no_result",
                                        "script_result_parse_error",
                                    )
                            )
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
                    }.onFailure {
                        if (
                            platform == MediaPlatform.Instagram &&
                            instagramApiStatus != "success_media" &&
                            instagramApiStatus != "login_required"
                        ) {
                            instagramApiStatus = "script_result_parse_error"
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
                    override fun onPageStarted(
                        view: WebView,
                        pageUrl: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        super.onPageStarted(view, pageUrl, favicon)
                        if (platform == MediaPlatform.Instagram) {
                            // Do not wait for onPageFinished: Instagram pages may keep loading
                            // indefinitely while the initial HTML/JS is already inspectable.
                            scheduleInspection(view, 900L)
                            scheduleInspection(view, 2200L)
                            scheduleInspection(view, 4000L)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        observe(request.url.toString(), request.requestHeaders)
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        finalUrl = pageUrl
                        scheduleInspection(view, 450L)
                        scheduleInspection(view, 1400L)
                        scheduleInspection(view, 2600L)
                        scheduleInspection(view, 4200L)
                        main.postDelayed({ if (mediaUrls.isNotEmpty()) finish() }, 6200L)
                    }
                }
                timeout = Runnable { finish() }
                val sessionTimeoutMs = if (platform == MediaPlatform.Instagram) 14000L else 10000L
                main.postDelayed(timeout!!, sessionTimeoutMs)
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
