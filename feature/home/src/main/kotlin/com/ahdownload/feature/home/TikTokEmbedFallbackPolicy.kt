package com.ahdownload.feature.home

import java.net.URI

/**
 * Builds only TikTok's public embed URL from a canonical HTTPS TikTok video page.
 * This does not resolve short links, authenticate, or bypass an access gate.
 */
internal object TikTokEmbedFallbackPolicy {
    fun publicEmbedUrl(pageUrl: String?): String? = runCatching {
        val value = pageUrl?.trim()?.takeIf(String::isNotEmpty)
            ?: return@runCatching null
        val uri = URI(value)
        if (uri.scheme?.lowercase() != "https") return@runCatching null
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (host != "tiktok.com" && !host.endsWith(".tiktok.com")) {
            return@runCatching null
        }

        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val videoIndex = segments.indexOfLast { it.equals("video", ignoreCase = true) }
        val videoId = segments.getOrNull(videoIndex + 1)
            ?.takeIf { videoIndex >= 0 && it.matches(Regex("""\d{6,30}""")) }
            ?: return@runCatching null

        "https://www.tiktok.com/embed/v2/$videoId"
    }.getOrNull()

    fun isPublicEmbedUrl(value: String?): Boolean = runCatching {
        if (value.isNullOrBlank()) return@runCatching false
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return@runCatching false
        uri.scheme?.lowercase() == "https" &&
            (host == "tiktok.com" || host.endsWith(".tiktok.com")) &&
            uri.path.orEmpty().matches(Regex("""/embed/v2/\d{6,30}/?"""))
    }.getOrDefault(false)
}
