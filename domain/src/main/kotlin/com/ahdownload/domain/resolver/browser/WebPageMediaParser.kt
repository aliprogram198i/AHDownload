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
        fun meta(name: String): String? {
            val e = Regex.escape(name)
            return Regex("""(?is)<meta[^>]+(?:property|name)=["']$e["'][^>]+content=["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
                ?: Regex("""(?is)<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$e["']""")
                    .find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
        }

        fun absolute(raw: String): String {
            val value = decodeHtml(raw).replace("\\/","/").trim()
            return if (value.startsWith("http://") || value.startsWith("https://")) value
            else runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
        }

        val title = meta("og:title")
            ?: meta("twitter:title")
            ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
        val thumbnail = (meta("og:image") ?: meta("twitter:image"))?.let(::absolute)
        val durationMs = (meta("video:duration") ?: meta("og:video:duration"))
            ?.toLongOrNull()?.takeIf { it > 0L }?.times(1000L)

        val urls = buildList {
            listOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")
                .mapNotNull(::meta).forEach { add(absolute(it)) }
            Regex("""(?i)(?:video|audio|source)[^>]+(?:src|data-src)=["']([^"']+)["']""")
                .findAll(html).forEach { add(absolute(it.groupValues[1])) }
            Regex("""(?i)https?://[^\s"'<>]+\.(?:mp4|m4v|webm|mov|mkv|m4a|mp3|aac|ogg|flac)(?:\?[^\s"'<>]*)?""")
                .findAll(html).forEach { add(it.value) }
        }.filter { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot { it.contains(".m3u8", true) || it.contains(".mpd", true) }
            .distinct()
            .take(64)

        return ParsedPageMedia(
            title = title?.trim()?.takeIf(String::isNotBlank),
            thumbnailUrl = thumbnail,
            durationMs = durationMs,
            mediaUrls = urls,
        )
    }

    private fun decodeHtml(value: String): String =
        value.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }.getOrDefault(it) }
            .trim()
}
