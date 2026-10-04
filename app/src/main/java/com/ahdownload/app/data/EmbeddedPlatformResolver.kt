package com.ahdownload.app.data

import android.content.Context
import com.ahdownload.app.diagnostics.AppLogger
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class EmbeddedPlatformResolver(
    private val context: Context? = null
) {
    suspend fun resolve(url: String): Result<ResolvedMedia> {
        val cleanUrl = url
            .replace(Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200D\\uFEFF]"), "")
            .trim()
        return withContext(Dispatchers.IO) {
            runCatching {
                require(cleanUrl.isNotBlank()) { "INVALID_URL" }
                val python = com.chaquo.python.Python.getInstance()
                val module = python.getModule("resolver")

                fun call(cookies: String?): ResolvedMedia {
                    val payload = JSONObject()
                        .put("url", cleanUrl)
                        .apply { if (!cookies.isNullOrBlank()) put("cookies", cookies) }
                    val raw = module.callAttr("resolve_json", payload.toString()).toString()
                    val json = JSONObject(raw)
                    if (json.has("error")) error(json.optString("error", "EXTRACTION_FAILED"))
                    val array = json.optJSONArray("formats") ?: error("NO_MEDIA_FORMATS")
                    val formats = buildList {
                        for (i in 0 until array.length()) {
                            val f = array.getJSONObject(i)
                            val mediaUrl = f.optString("url")
                            if (mediaUrl.isBlank()) continue
                            add(
                                ResolvedFormat(
                                    f.optString("id"),
                                    f.optString("ext"),
                                    f.optInt("width").takeIf { f.has("width") && it > 0 },
                                    f.optInt("height").takeIf { f.has("height") && it > 0 },
                                    f.optDouble("abr").takeIf { f.has("abr") },
                                    f.optLong("sizeBytes").takeIf { f.has("sizeBytes") && it > 0 },
                                    f.optBoolean("hasVideo"),
                                    f.optBoolean("hasAudio"),
                                    mediaUrl,
                                    f.optBoolean("mergeRequired"),
                                    f.optString("audioUrl").takeIf { it.isNotBlank() },
                                    f.optString("audioExt").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                    require(formats.isNotEmpty()) { "NO_MEDIA_FORMATS" }
                    return ResolvedMedia(
                        json.optString("title", "AHDownload file"),
                        json.optString("thumbnail").takeIf { it.isNotBlank() },
                        json.optDouble("duration").takeIf { json.has("duration") },
                        json.optString("extractor").takeIf { it.isNotBlank() },
                        json.optString("source", cleanUrl),
                        formats
                    )
                }

                try {
                    context?.let { AppLogger.info(it, "resolver.start", "host=" + android.net.Uri.parse(cleanUrl).host.orEmpty()) }
                    call(CookieManager.getInstance().getCookie(cleanUrl))
                } catch (first: Throwable) {
                    val host = android.net.Uri.parse(cleanUrl).host.orEmpty().lowercase()
                    val sessionEligible =
                        host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host == "instagram.com" || host.endsWith(".instagram.com")

                    if (!sessionEligible || context == null) throw first

                    val cookies = WebViewSessionBridge(context).cookiesFor(cleanUrl)
                    if (cookies.isNullOrBlank()) throw first
                    AppLogger.info(context, "resolver.webview_session", "cookies_obtained=true")
                    call(cookies)
                }
            }.onFailure { failure ->
                context?.let { AppLogger.error(it, "resolver.failed", failure, "host=" + android.net.Uri.parse(cleanUrl).host.orEmpty()) }
            }
        }
    }
}
