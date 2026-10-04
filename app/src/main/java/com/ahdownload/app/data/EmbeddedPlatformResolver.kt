package com.ahdownload.app.data

import android.content.Context
import android.webkit.CookieManager
import com.ahdownload.app.diagnostics.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLConnection
import java.util.concurrent.TimeUnit

class EmbeddedPlatformResolver(
    private val context: Context? = null
) {
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun resolve(url: String): Result<ResolvedMedia> {
        val cleanUrl = url.replace(Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200D\\uFEFF]"), "").trim()
        return withContext(Dispatchers.IO) {
            runCatching {
                require(cleanUrl.isNotBlank()) { "INVALID_URL" }
                val python = com.chaquo.python.Python.getInstance()
                val module = python.getModule("resolver")

                fun call(cookies: String?): ResolvedMedia {
                    val payload = JSONObject().put("url", cleanUrl)
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
                            add(ResolvedFormat(
                                f.optString("id"), f.optString("ext"),
                                f.optInt("width").takeIf { f.has("width") && it > 0 },
                                f.optInt("height").takeIf { f.has("height") && it > 0 },
                                f.optDouble("abr").takeIf { f.has("abr") },
                                f.optLong("sizeBytes").takeIf { f.has("sizeBytes") && it > 0 },
                                f.optBoolean("hasVideo"), f.optBoolean("hasAudio"), mediaUrl,
                                f.optBoolean("mergeRequired"),
                                f.optString("audioUrl").takeIf { it.isNotBlank() },
                                f.optString("audioExt").takeIf { it.isNotBlank() }
                            ))
                        }
                    }
                    require(formats.isNotEmpty()) { "NO_MEDIA_FORMATS" }
                    return ResolvedMedia(
                        json.optString("title", "AHDownload file"),
                        json.optString("thumbnail").takeIf { it.isNotBlank() },
                        json.optDouble("duration").takeIf { json.has("duration") },
                        json.optString("extractor").takeIf { it.isNotBlank() },
                        json.optString("source", cleanUrl), formats
                    )
                }

                val host = android.net.Uri.parse(cleanUrl).host.orEmpty().lowercase()
                try {
                    context?.let { AppLogger.info(it, "resolver.start", "host=" + host) }
                    call(CookieManager.getInstance().getCookie(cleanUrl))
                } catch (first: Throwable) {
                    val sessionEligible =
                        host == "youtube.com" || host.endsWith(".youtube.com") ||
                            host == "youtu.be" ||
                            host == "instagram.com" || host.endsWith(".instagram.com") ||
                            host == "facebook.com" || host.endsWith(".facebook.com")
                    if (!sessionEligible || context == null) throw first

                    val snapshot = WebViewSessionBridge(context).snapshotFor(cleanUrl)
                    AppLogger.info(
                        context, "resolver.webview_session",
                        "cookies_obtained=" + (!snapshot.cookies.isNullOrBlank()) +
                            " authenticated=" + snapshot.authenticated +
                            " candidates=" + snapshot.mediaUrls.size
                    )

                    if (!snapshot.cookies.isNullOrBlank()) {
                        try {
                            return@runCatching call(snapshot.cookies)
                        } catch (sessionFailure: Throwable) {
                            if (isInstagram(host)) {
                                probeWebViewMedia(cleanUrl, snapshot)?.let {
                                    AppLogger.info(context, "resolver.webview_media_fallback",
                                        "candidates=" + snapshot.mediaUrls.size + " validated=true")
                                    return@runCatching it
                                }
                            }
                            throw sessionFailure
                        }
                    }

                    if (isInstagram(host)) {
                        probeWebViewMedia(cleanUrl, snapshot)?.let {
                            AppLogger.info(context, "resolver.webview_media_fallback",
                                "candidates=" + snapshot.mediaUrls.size + " validated=true")
                            return@runCatching it
                        }
                    }
                    throw first
                }
            }.onFailure { failure ->
                context?.let {
                    AppLogger.error(it, "resolver.failed", failure,
                        "host=" + android.net.Uri.parse(cleanUrl).host.orEmpty())
                }
            }
        }
    }

    private fun isInstagram(host: String): Boolean =
        host == "instagram.com" || host.endsWith(".instagram.com")

    private fun probeWebViewMedia(sourceUrl: String, snapshot: WebViewMediaSnapshot): ResolvedMedia? {
        data class Candidate(val url: String, val contentType: String, val size: Long?, val score: Int)
        val validated = mutableListOf<Candidate>()

        for (candidate in snapshot.mediaUrls.distinct()) {
            runCatching {
                val builder = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "video/*,audio/*,*/*;q=0.8")
                    .header("Referer", sourceUrl)
                    .header("Range", "bytes=0-1023")
                snapshot.cookies?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

                probeClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) return@use
                    val contentType = response.header("Content-Type")
                        ?.substringBefore(";")?.trim()?.lowercase().orEmpty()
                    val guessedType = URLConnection.guessContentTypeFromName(
                        candidate.substringBefore('?').substringBefore('#')
                    ).orEmpty().lowercase()

                    if (contentType.startsWith("text/") ||
                        contentType == "application/xhtml+xml" ||
                        contentType == "application/json" ||
                        contentType == "application/javascript" ||
                        contentType == "application/vnd.apple.mpegurl" ||
                        contentType == "application/x-mpegurl" ||
                        guessedType == "application/vnd.apple.mpegurl"
                    ) return@use

                    val isVideo = contentType.startsWith("video/") ||
                        guessedType.startsWith("video/") || hasMediaExtension(candidate)
                    val isAudio = contentType.startsWith("audio/") || guessedType.startsWith("audio/")
                    if (!isVideo && !isAudio) return@use

                    val size = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                    val score = (if (contentType.startsWith("video/")) 100 else 0) +
                        (if (contentType == "video/mp4") 30 else 0) +
                        (if (response.code == 206) 10 else 0) +
                        (if (size != null) 5 else 0)
                    validated += Candidate(candidate, contentType, size, score)
                }
            }
        }

        val best = validated.maxByOrNull { it.score } ?: return null
        val isVideo = best.contentType.startsWith("video/") ||
            hasMediaExtension(best.url) || !best.contentType.startsWith("audio/")

        return ResolvedMedia(
            title = snapshot.title?.takeIf { it.isNotBlank() } ?: "Instagram video",
            thumbnail = null, durationSeconds = null, extractor = "InstagramWebView",
            source = sourceUrl,
            formats = listOf(ResolvedFormat(
                id = "instagram-webview-" + best.score,
                ext = extensionFor(best.contentType, best.url),
                width = null, height = null, abr = null, sizeBytes = best.size,
                hasVideo = isVideo, hasAudio = isVideo, url = best.url
            ))
        )
    }

    private fun hasMediaExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m4v") ||
            path.endsWith(".webm") || path.endsWith(".mov")
    }

    private fun extensionFor(contentType: String, url: String): String {
        val guessed = URLConnection.guessContentTypeFromName(url.substringBefore('?').substringBefore('#'))
        return when {
            contentType == "video/mp4" || guessed == "video/mp4" -> "mp4"
            contentType == "video/webm" || guessed == "video/webm" -> "webm"
            contentType == "video/quicktime" -> "mov"
            contentType == "audio/mp4" -> "m4a"
            contentType == "audio/mpeg" -> "mp3"
            contentType == "audio/webm" -> "webm"
            else -> "mp4"
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "Chrome/140.0 Mobile Safari/537.36"
    }
}
