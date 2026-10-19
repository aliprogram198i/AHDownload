package com.ahdownload.feature.home

import java.net.URI

/**
 * Identifies media URLs observed inside TikTok's public WebView session.
 *
 * TikTok CDN URLs do not always end in a video extension. Some carry their media type in
 * `mime_type=video_mp4`, while others expose a `/video/tos/` path. Restrict this fallback
 * to known TikTok media hosts and reject known image extensions to avoid treating thumbnails
 * or unrelated page resources as downloadable media.
 */
internal object TikTokMediaUrlClassifier {
    private val trustedHostSuffixes = setOf(
        "tiktokcdn.com",
        "tiktokcdn-us.com",
        "tiktokcdn-eu.com",
        "tiktokcdn-in.com",
        "tiktokrow-cdn.com",
        "tiktok-row.net",
        "tiktokv.com",
        "tiktokv.eu",
        "tiktokv.us",
        "tiktokvapp.com",
        "byteoversea.com",
        "byteoversea.net",
        "ibytedtos.com",
        "muscdn.com",
        "ibyteimg.com",
        "byteimg.com",
        "pstatp.com",
    )

    private val mediaExtensions = setOf(
        "mp4", "m4v", "webm", "mov", "m3u8", "mpd",
        "m4a", "mp3", "aac", "ogg", "flac", "wav",
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif",
    )

    fun isMediaResource(rawUrl: String): Boolean = runCatching {
        val uri = URI(rawUrl.trim())
        if (uri.scheme?.lowercase() != "https") return@runCatching false
        val host = uri.host?.lowercase() ?: return@runCatching false
        if (trustedHostSuffixes.none { host == it || host.endsWith(".$it") }) {
            return@runCatching false
        }

        val path = uri.path.orEmpty().lowercase()
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extension in imageExtensions) return@runCatching false
        if (extension in mediaExtensions) return@runCatching true

        val mimeType = uri.rawQuery.orEmpty()
            .split('&')
            .firstNotNullOfOrNull { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    parameter.substring(0, separator)
                        .lowercase()
                        .takeIf { it in MIME_QUERY_KEYS }
                        ?.let { parameter.substring(separator + 1).lowercase() }
                }
            }

        mimeType?.startsWith("video") == true ||
            mimeType?.startsWith("audio") == true ||
            "/video/tos/" in path ||
            Regex("""/video/tos(?:[-/]|$)""").containsMatchIn(path)
    }.getOrDefault(false)

    private val MIME_QUERY_KEYS = setOf("mime_type", "mime", "content-type", "type")
}
