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
import kotlin.coroutines.resume

class AndroidYouTubeSessionProvider(private val context: Context) : YouTubeSessionProvider {
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun snapshot(url: String): YouTubeSessionSnapshot =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var timeout: Runnable? = null
            val videos = linkedSetOf<String>()
            val audios = linkedSetOf<String>()
            var playerResponse: String? = null
            var authenticated = false

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

            fun finish() {
                if (finished) return
                finished = true
                timeout?.let(main::removeCallbacks)
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
                        ),
                    )
                }
            }

            fun inspect(view: WebView, attempt: Int) {
                if (finished) return
                val script = """(function(){
                    const v=new Set(),a=new Set();
                    const add=(s,x)=>{if(!x)return;try{x=new URL(x,location.href).href}catch(_){} 
                      if(/^https?:\/\//i.test(x)&&!/.m3u8(?:[?#]|$)/i.test(x))s.add(x)};
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
                      if(/(?:[?&](?:mime|type)=audio%2f|[?&](?:mime|type)=audio\/|audio)/i.test(l))a.add(u);
                      else if(/(?:[?&](?:mime|type)=video%2f|[?&](?:mime|type)=video\/|\.mp4|\.webm|\.m4v|\.mov)/i.test(l))v.add(u)
                    })}catch(_){}
                    let p=null;
                    try{
                      if(window.ytInitialPlayerResponse)p=JSON.stringify(window.ytInitialPlayerResponse);
                      else if(window.ytplayer&&window.ytplayer.config&&window.ytplayer.config.args&&window.ytplayer.config.args.player_response)
                        p=window.ytplayer.config.args.player_response;
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

                    if (attempt >= 8) {
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
                        if (failingUrl == url) finish()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) finish()
                    }

                    override fun onLoadResource(view: WebView, resourceUrl: String) {
                        val lower = resourceUrl.lowercase()
                        if ((resourceUrl.startsWith("https://") || resourceUrl.startsWith("http://")) &&
                            !lower.contains(".m3u8")
                        ) {
                            when {
                                "mime=audio" in lower || "type=audio" in lower -> add(audios, resourceUrl)
                                lower.contains(".mp4") || lower.contains(".webm") ||
                                    lower.contains(".m4v") || lower.contains(".mov") -> add(videos, resourceUrl)
                            }
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
