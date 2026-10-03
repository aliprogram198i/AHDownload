package com.ahdownload.app.data

import android.content.Context
import com.ahdownload.app.diagnostics.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class EmbeddedPlatformResolver(
    private val context: Context? = null
) {
    suspend fun resolve(url: String): Result<ResolvedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val python = com.chaquo.python.Python.getInstance()
            val module = python.getModule("resolver")

            fun call(cookies: String?): ResolvedMedia {
                val payload = JSONObject()
                    .put("url", url)
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
                                mediaUrl
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
                    json.optString("source", url),
                    formats
                )
            }

            try {
                AppLogger.info(context ?: return@runCatching call(null), "resolver.start", "host=" + android.net.Uri.parse(url).host.orEmpty())
                call(null)
            } catch (first: Throwable) {
                val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
                val sessionEligible =
                    host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtu.be" || host == "instagram.com" || host.endsWith(".instagram.com")

                if (!sessionEligible || context == null) throw first

                val cookies = WebViewSessionBridge(context).cookiesFor(url)
                if (cookies.isNullOrBlank()) throw first
                AppLogger.info(context, "resolver.webview_session", "cookies_obtained=true")
                call(cookies)
            }
        }.onFailure { failure ->
            context?.let { AppLogger.error(it, "resolver.failed", failure, "host=" + android.net.Uri.parse(url).host.orEmpty()) }
        }
    }
}
