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

/**
 * Obtains platform session cookies locally from Android WebView.
 *
 * Cookies never leave the device and are only held in memory for the current
 * extraction attempt. This is a fallback for platforms that reject a clean
 * yt-dlp request with bot/session checks.
 */
class WebViewSessionBridge(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun cookiesFor(url: String, timeoutMs: Long = 25_000L): String? =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false

            fun finish(value: String?) {
                if (finished) return
                finished = true
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(value)
            }

            val timeout = Runnable { finish(CookieManager.getInstance().getCookie(url)) }

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
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/140.0 Mobile Safari/537.36"

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        main.postDelayed({
                            finish(cookieManager.getCookie(url))
                        }, 1_500L)
                    }

                    @Deprecated("Deprecated in API 23")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        finish(cookieManager.getCookie(url))
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            finish(cookieManager.getCookie(url))
                        }
                    }
                }

                main.postDelayed(timeout, timeoutMs)
                view.loadUrl(url)
            }

            continuation.invokeOnCancellation {
                main.post { finish(null) }
            }
        }
}
