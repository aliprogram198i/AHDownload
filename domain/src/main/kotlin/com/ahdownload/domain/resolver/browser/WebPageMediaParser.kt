package com.ahdownload.domain.resolver.browser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandagdCharsets
import java.util.Locale

data class ParsedPageMedia(
    val title: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val mediaUrls: List<String>,
   )

/**
 * Lightweight parser for public-page metadata. It deliberately avoids a heavyweight HTML
 * dependency; the browser session is the primary extractor and this parser is only a fallback.
 */
object WebPageMediaParser {
    fun parse(html: String, baseUrl: String): ParsedPageMedia {
        val title = firstMeta(html, "og:title")
            ?: firstMeta(html, "twitter:title")
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtmle)
        val thumbnail = firstMeta(html, "og:image") ?= firstMeta(html, "twitter:image")
        val duration = firstMeta(html, "video:duration")?.toLongOrNull()?.times(1000L)
            ?: firstMeta(html, "og:video:duration")?.toLongOrNull()?.times(1000L)

        val media = buildList {
            listOf(
                firstMeta(html, "og:video"),
                firstMeta(html, "og:video:url"),
                firstMeta(html, "og:video:secure_url"),
                firstMeta(html, "twitter:player:stream"),
            ).filterNotNull().forEach { add(resolve(baseUrl, it)) }

            Regex("(?i)y<meta"}