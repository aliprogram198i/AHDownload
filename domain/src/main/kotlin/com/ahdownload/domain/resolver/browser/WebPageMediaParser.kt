package com.ahdownload.domain.resolver.browser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ParsedPageMedia(
    val title: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val mediaUrls: List<String>,
)

object WebPageMediaParser {
    fun parse(html: String, baseUrl: String): ParsedPageMedia {
        // Instagram frequently embeds media URLs inside JSON/JS using escaped
        // slashes and unicode URL delimiters instead of normal HTML media tags.
        // Normalize only URL-relevant escapes before running the media scanner.
        val searchableHtml = normalizeEmbeddedUrlEscapes(html)

        fun meta(name: String): String? {
            val e = Regex.escape(name)
            return Regex("""(?is)<meta[^>]+(?:property|name)=["']$e["'][^>]+content=["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
                ?: Regex("""(?is)<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$e["']""")
                    .find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
        }

        fun absolute(raw: String): String {
            val value = decodeHtml(raw).trim()
            return if (value.startsWith("http://") || value.startsWith("https://")) value
            else runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
        }

        val title = meta("og:title")
            ?: meta("twitter:title")
            ?: Regex("""(?is)<title[^>]*>(.*?)</title>""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
        val thumbnail = (meta("og:image") ?: meta("twitter:image"))?.let(::absolute)
        val durationMs = (meta("video:duration") ?: meta("og:video:duration"))
            ?.toLongOrNull()?.takeIf { it > 0L }?.times(1000L)

        val urls = buildList {
            listOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")
                .mapNotNull(::meta)
                .forEach { add(absolute(it)) }

            Regex("""(?i)(?:video|audio|source)[^>]+(?:src|data-src)=["']([^"']+)["']""")
                .findAll(html)
                .forEach { add(absolute(it.groupValues[1])) }

            // Instagram application state commonly exposes direct media under
            // one of these keys even when OpenGraph/video elements are absent.
            Regex("""(?is)"(?:video_url|playback_url|videoUrl|contentUrl|content_url|player_url|stream_url)"\s*:\s*"([^"]+)"""")
                .findAll(searchableHtml)
                .forEach { add(absolute(it.groupValues[1])) }

            // Final pass catches escaped CDN URLs that do not sit under an HTML
            // media element. The media predicate prevents ordinary page URLs
            // from becoming candidates.
            Regex("""(?i)https?://[^\s"'<>\\]+""")
                .findAll(searchableHtml)
                .map { it.value }
                .filter(::isLikelyMediaUrl)
                .forEach { add(it) }
        }
            .map(::decodeHtml)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .filter(::isLikelyMediaUrl)
            .distinct()
            .take(64)

        return ParsedPageMedia(
            title = title?.trim()?.takeIf(String::isNotBlank),
            thumbnailUrl = thumbnail,
            durationMs = durationMs,
            mediaUrls = urls,
        )
    }

    private fun isLikelyMediaUrl(raw: String): Boolean {
        val value = raw.trim().lowercase()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false

        val path = value.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "")
        if (extension in MEDIA_EXTENSIONS) return true

        if (Regex("""[?&](?:mime|content-type|type|format)=[^&]*?(?:video|audio)""")
                .containsMatchIn(value)
        ) return true

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

    private fun normalizeEmbeddedUrlEscapes(value: String): String =
        value
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)

    private fun decodeHtml(value: String): String {
        val htmlDecoded = normalizeEmbeddedUrlEscapes(value)
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

        // URLDecoder treats '+' as a space. Only apply it when a percent-encoded
        // sequence is actually present so literal '+' characters in media URLs
        // are preserved.
        return if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(htmlDecoded)) {
            runCatching {
                URLDecoder.decode(htmlDecoded, StandardCharsets.UTF_8.toString())
            }.getOrDefault(htmlDecoded)
        } else {
            htmlDecoded
        }
    }

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mov", "mkv", "m4a", "mp3", "aac", "ogg", "flac", "wav",
        "m3u8", "mpd",
    )
}
