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
        .build()

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

                val host = android.net.Uri.parse(cleanUrl).host.orEmpty().lowercase()
                try {
                    context?.let { AppLogger.info(it, "resolver.start", "host=" + host) }
                    call(CookieManager.getInstance().getCookie(cleanUrl))
                } catch (first: Throwable) {
                    val sessionEligible =
                        host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host == "instagram.com" || host.endsWith(".instagram.com") ||
                        host == "facebook.com" || host.endsWith(".facebook.com")

                    if (!sessionEligible || context == null) throw first

                    val bridge = WebViewSessionBridge(context)
                    val snapshot = bridge.snapshotFor(cleanUrl)
                    if (!snapshot.cookies.isNullOrBlank()) {
                        AppLogger.info(context, "resolver.webview_session", "cookies_obtained=" + (!snapshot.cookies.isNullOrBlank()) + " authenticated=" + snapshot.authenticated + " candidates=" + snapshot.mediaUrls.size)
                        try {
                            return@runCatching call(snapshot.cookies)
                        } catch (sessionFailure: Throwable) {
                            if (host == "instagram.com" || host.endsWith(".instagram.com")) {
                                val webViewMedia = probeWebViewMedia(cleanUrl, snapshot)
                                if (webViewMedia != null) {
                                    AppLogger.info(
                                        context,
                                        "resolver.webview_media_fallback",
                                        "candidates=" + snapshot.mediaUrls.size
                                    )
                                    return@runCatching webViewMedia
                                }
                            }
                            throw sessionFailure
                        }
                    }

                    if (host == "instagram.com" || host.endsWith(".instagram.com")) {
                        val webViewMedia = probeWebViewMedia(cleanUrl, snapshot)
                        if (webViewMedia != null) {
                            AppLogger.info(
                                context,
                                "resolver.webview_media_fallback",
                                "candidates=" + snapshot.mediaUrls.size
                            )
                            return@runCatching webViewMedia
                        }
                    }

                    throw first
                }
            }.onFailure { failure ->
                context?.let {
                    AppLogger.error(
                        it,
                        "resolver.failed",
                        failure,
                        "host=" + android.net.Uri.parse(cleanUrl).host.orEmpty()
                    )
                }
            }
        }
    }

    private fun probeWebViewMedia(sourceUrl: String, snapshot: WebViewMediaSnapshot): ResolvedMedia? {
        for (candidate in snapshot.mediaUrls.distinct().sortedByDescending(::mediaCandidateScore)) {
            runCatching {
                val builder = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Referer", sourceUrl)
                    .header("Range", "bytes=0-1023")
                snapshot.cookies?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

                probeClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) return@use
                    val contentType = response.header("Content-Type")
                        ?.substringBefore(";")
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                    if (contentType.startsWith("text/") || contentType == "application/xhtml+xml") return@use
                    if (contentType == "application/vnd.apple.mpegurl" || contentType == "application/x-mpegurl") return@use
                    val extensionLooksMedia = candidate.substringBefore("?").substringBefore("#").lowercase().let { it.endsWith(".mp4") || it.endsWith(".m4v") || it.endsWith(".webm") || it.endsWith(".mov") || it.endsWith(".m4a") || it.endsWith(".mp3") }
                    if (!contentType.startsWith("video/") && !contentType.startsWith("audio/") && !(contentType == "application/octet-stream" && extensionLooksMedia)) return@use

                    val isVideo = contentType.startsWith("video/") || extensionLooksMedia && candidate.substringBefore("?").substringBefore("#").lowercase().let { it.endsWith(".mp4") || it.endsWith(".m4v") || it.endsWith(".webm") || it.endsWith(".mov") }
                    val ext = extensionFor(contentType, candidate)
                    val size = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                    return ResolvedMedia(
                        title = snapshot.title?.takeIf { it.isNotBlank() } ?: "Instagram video",
                        thumbnail = null,
                        durationSeconds = null,
                        extractor = "InstagramWebView",
                        source = sourceUrl,
                        formats = listOf(
                            ResolvedFormat(
                                id = "instagram-webview",
                                ext = ext,
                                width = null,
                                height = null,
                                abr = null,
                                sizeBytes = size,
                                hasVideo = isVideo,
                                hasAudio = !isVideo,
                                url = candidate
                            )
                        )
                    )
                }
            }
        }
        return null
    }

    private fun mediaCandidateScore(url: String): Int {
        val lower = url.lowercase()
        var score = 0
        if (lower.contains("cdninstagram")) score += 40
        if (lower.contains("fbcdn") || lower.contains("scontent")) score += 30
        if (lower.contains(".mp4")) score += 50
        if (lower.contains(".m4v") || lower.contains(".mov")) score += 35
        if (lower.contains(".m3u8")) score -= 100
        if (lower.contains("thumbnail") || lower.contains("profile") || lower.contains("avatar")) score -= 60
        return score
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
