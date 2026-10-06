package com.ahdownload.domain.analyzer

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class LinkAnalyzer {
    fun analyze(rawUrl: String): MediaLink? {
        val extracted = extractHttpUrl(rawUrl) ?: return null
        val normalized = unwrapRedirect(extracted)
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https")) return null

        val host = normalizeHost(uri.host) ?: return null
        val kind = detectMediaKind(uri)

        val platform = when {
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                MediaPlatform.YouTube
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                MediaPlatform.Instagram
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" ->
                MediaPlatform.Facebook
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                MediaPlatform.TikTok
            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") ->
                MediaPlatform.X
            kind != MediaKind.Unknown -> MediaPlatform.DirectMedia
            else -> MediaPlatform.Unknown
        }

        return MediaLink(
            originalUrl = rawUrl,
            normalizedUrl = normalized,
            platform = platform,
            kind = kind,
        )
    }

    private fun extractHttpUrl(raw: String): String? {
        val cleaned = raw.trim().replace("&amp;", "&")
        val match = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(cleaned)
        val candidate = (match?.value ?: cleaned).trimEnd('.', ',', ';', ')', ']', '}')
        return candidate.takeIf { it.isNotBlank() }
    }

    private fun unwrapRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = normalizeHost(uri.host).orEmpty()
        if (host == "google.com" || host.endsWith(".google.com") ||
            host == "facebook.com" || host.endsWith(".facebook.com")
        ) {
            val query = parseQuery(uri.rawQuery)
            val target = query["url"] ?: query["u"] ?: query["q"]
            if (!target.isNullOrBlank() && target.startsWith("http", ignoreCase = true)) {
                return runCatching {
                    URLDecoder.decode(target, StandardCharsets.UTF_8.toString())
                }.getOrDefault(target)
            }
        }
        return url
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) null
                else runCatching {
                    URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.toString()) to
                        URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.toString())
                }.getOrNull()
            }
            .toMap()

    private fun normalizeHost(host: String?): String? =
        host?.trim('.')?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.takeIf { it.isNotBlank() }

    private fun detectMediaKind(uri: URI): MediaKind {
        val extension = uri.path
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val query = parseQuery(uri.rawQuery)
        val hinted = listOf(
            query["mime"],
            query["content_type"],
            query["format"],
            query["type"],
            query["ext"],
        )
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val mediaValue = "$extension $hinted"

        return when {
            mediaValue.contains("video/") || mediaValue.contains("video") -> MediaKind.Video
            mediaValue.contains("audio/") || mediaValue.contains("audio") -> MediaKind.Audio
            mediaValue.contains("image/") || mediaValue.contains("image") -> MediaKind.Image
            extension in setOf("mp4", "m4v", "webm", "mov", "mkv") -> MediaKind.Video
            extension in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac") -> MediaKind.Audio
            extension in setOf("jpg", "jpeg", "png", "webp", "gif") -> MediaKind.Image
            else -> MediaKind.Unknown
        }
    }
}
