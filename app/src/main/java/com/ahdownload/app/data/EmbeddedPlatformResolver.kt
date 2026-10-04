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

    suspend fun resolve(
        url: String,
        excludedUrls: Set<String> = emptySet(),
        forceFresh: Boolean = false
    ): Result<ResolvedMedia> {
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
                                    f.optString("audioExt").takeIf { it.isNotBlank() },
                                    parseHeaders(f.optJSONObject("httpHeaders")),
                                    parseHeaders(f.optJSONObject("audioHeaders"))
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
                context?.let { AppLogger.info(it, "resolver.start", "host=" + host) }

                // Instagram gets one deterministic session snapshot: use its cookies with yt-dlp,
                // then probe only the verified media candidates from that same WebView session.
                if (isInstagramHost(host) && context != null) {
                    val snapshot = WebViewSessionBridge(context).snapshotFor(cleanUrl, forceFresh = forceFresh)
                    AppLogger.info(
                        context,
                        "resolver.instagram_session",
                        "cookies_obtained=" + !snapshot.cookies.isNullOrBlank() +
                            " authenticated=" + snapshot.authenticated +
                            " candidates=" + snapshot.mediaUrls.size
                    )
                    if (!snapshot.cookies.isNullOrBlank()) {
                        runCatching { call(snapshot.cookies) }.onSuccess {
                            return@runCatching it
                        }
                    }
                    val webViewMedia = probeWebViewMedia(cleanUrl, snapshot, excludedUrls)
                    if (webViewMedia != null) {
                        AppLogger.info(
                            context,
                            "resolver.instagram_media_fallback",
                            "candidates=" + snapshot.mediaUrls.size
                        )
                        return@runCatching webViewMedia
                    }
                    // Preserve the original extraction error when the session yielded no usable media.
                    return@runCatching call(null)
                }

                try {
                    call(CookieManager.getInstance().getCookie(cleanUrl))
                } catch (first: Throwable) {
                    val sessionEligible =
                        host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" ||
                        host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch"

                    if (!sessionEligible || context == null) throw first

                    if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be") {
                        AppLogger.info(
                            context,
                            "resolver.youtube_extraction_failure",
                            "reason=" + youtubeFailureClass(first)
                        )
                    }

                    val bridge = WebViewSessionBridge(context)
                    val snapshot = bridge.snapshotFor(cleanUrl)
                    if (!snapshot.cookies.isNullOrBlank()) {
                        AppLogger.info(
                            context,
                            "resolver.webview_session",
                            "cookies_obtained=true authenticated=" + snapshot.authenticated +
                                " candidates=" + snapshot.mediaUrls.size
                        )
                        try {
                            return@runCatching call(snapshot.cookies)
                        } catch (sessionFailure: Throwable) {
                            if (isFacebookHost(host)) {
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

                    if (isFacebookHost(host)) {
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

    private fun probeWebViewMedia(
        sourceUrl: String,
        snapshot: WebViewMediaSnapshot,
        excludedUrls: Set<String> = emptySet()
    ): ResolvedMedia? {
        val isInstagram = sourceUrl.contains("instagram.", ignoreCase = true)
        val origin = if (sourceUrl.contains("facebook.", ignoreCase = true) || sourceUrl.contains("fb.watch", ignoreCase = true)) {
            "https://www.facebook.com"
        } else {
            "https://www.instagram.com"
        }

        for (candidate in snapshot.mediaUrls.distinct().filterNot { it in excludedUrls }.sortedByDescending(::mediaCandidateScore)) {
            val path = candidate.substringBefore("?").substringBefore("#").lowercase()
            val extensionLooksMedia = path.endsWith(".mp4") || path.endsWith(".m4v") ||
                path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".m4a") || path.endsWith(".mp3")

            val variants = listOf(
                true to "video/avc,video/mp4,video/*;q=0.9,*/*;q=0.8",
                false to "*/*"
            )
            for ((withCookie, accept) in variants) {
                val builder = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", accept)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", sourceUrl)
                    .header("Origin", origin)
                    .header("Sec-Fetch-Dest", if (isInstagram) "video" else "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "cross-site")
                    .header("Range", "bytes=0-4095")
                if (withCookie) {
                    snapshot.cookies?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
                }

                val validated = runCatching {
                    probeClient.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) return@use null

                        val contentType = response.header("Content-Type")
                            ?.substringBefore(";")
                            ?.trim()
                            ?.lowercase()
                            .orEmpty()
                        if (contentType.startsWith("text/") || contentType == "application/xhtml+xml" ||
                            contentType == "application/vnd.apple.mpegurl" || contentType == "application/x-mpegurl"
                        ) return@use null

                        val sample = response.body?.byteStream()?.use { it.readNBytes(4096) } ?: ByteArray(0)
                        val signatureLooksMedia =
                            sample.size >= 8 && (
                                String(sample, 4, 4, Charsets.US_ASCII) == "ftyp" ||
                                    (sample.size >= 4 && sample[0] == 0x1A.toByte() && sample[1] == 0x45.toByte() &&
                                        sample[2] == 0xDF.toByte() && sample[3] == 0xA3.toByte())
                                )
                        val dispositionHeader = response.header("Content-Disposition").orEmpty().lowercase()
                        val dispositionLooksMedia = listOf(".mp4", ".m4v", ".webm", ".mov", ".m4a", ".mp3")
                            .any(dispositionHeader::contains)

                        // A CDN hostname or a media-looking path is not proof that the response is media.
                        // Instagram can return a small signed-error/XML/HTML payload from the same CDN URL.
                        // Require an actual media Content-Type/signature before exposing a candidate to analysis.
                        val contentTypeLooksVideo = contentType.startsWith("video/")
                        val contentTypeLooksAudio = contentType.startsWith("audio/")
                        val extensionLooksVideo = extensionLooksMedia &&
                            !path.endsWith(".m4a") && !path.endsWith(".mp3")
                        val extensionLooksAudio = path.endsWith(".m4a") || path.endsWith(".mp3")
                        val looksVideo = contentTypeLooksVideo || signatureLooksMedia ||
                            (extensionLooksVideo && signatureLooksMedia)
                        val looksAudio = contentTypeLooksAudio ||
                            (extensionLooksAudio && signatureLooksMedia)

                        if (!looksVideo && !looksAudio && !dispositionLooksMedia) return@use null
                        if (dispositionLooksMedia && !signatureLooksMedia &&
                            !contentTypeLooksVideo && !contentTypeLooksAudio) return@use null

                        val ext = extensionFor(
                            contentType,
                            response.header("Content-Disposition").orEmpty().ifBlank { candidate }
                        )
                        val size = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                        ResolvedMedia(
                            title = snapshot.title?.takeIf { it.isNotBlank() }
                                ?: if (sourceUrl.contains("facebook.", true) || sourceUrl.contains("fb.watch", true)) "Facebook video" else "Instagram video",
                            thumbnail = null,
                            durationSeconds = null,
                            extractor = if (sourceUrl.contains("facebook.", ignoreCase = true) || sourceUrl.contains("fb.watch", ignoreCase = true)) "FacebookWebView" else "InstagramWebView",
                            source = sourceUrl,
                            formats = listOf(
                                ResolvedFormat(
                                    id = if (isInstagram) "instagram-webview" else "facebook-webview",
                                    ext = ext,
                                    width = null,
                                    height = null,
                                    abr = null,
                                    sizeBytes = size,
                                    hasVideo = looksVideo,
                                    hasAudio = looksAudio || looksVideo,
                                    url = candidate
                                )
                            )
                        )
                    }
                }.getOrNull()

                if (validated != null) return validated
            }
        }
        return null
    }

    private fun parseHeaders(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key).trim()
            if (value.isNotBlank()) result[key] = value
        }
        return result
    }

    private fun youtubeFailureClass(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "sign in" in message || "login" in message || "age-restricted" in message ->
                "AUTH_REQUIRED"
            "po token" in message || "proof of origin" in message ->
                "PO_TOKEN_REQUIRED"
            "bot" in message || "captcha" in message || "challenge" in message ->
                "BOT_CHECK"
            "403" in message || "forbidden" in message ->
                "HTTP_403"
            "429" in message || "too many requests" in message ->
                "RATE_LIMITED"
            else -> "EXTRACTION_FAILED"
        }
    }

    private fun isInstagramHost(host: String): Boolean =
        host == "instagram.com" || host.endsWith(".instagram.com")

    private fun isFacebookHost(host: String): Boolean =
        host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch"

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

