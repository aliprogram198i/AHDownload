package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchKind
import com.ahdownload.domain.search.ContentSearchProvider
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class YouTubeSearchProvider(
    private val httpClient: HttpTextClient,
) : ContentSearchProvider {
    override suspend fun search(query: String, limit: Int): List<ContentSearchItem> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotBlank()) { "Search query must not be blank" }

        val encoded = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8.toString())
        val html = httpClient.get(
            "https://www.youtube.com/results?search_query=$encoded",
            headers = mapOf(
                "Accept-Language" to "en-US,en;q=0.9",
                "User-Agent" to "Mozilla/5.0",
            ),
        )

        val root = extractInitialData(html)
            ?: throw IllegalStateException("YouTube search payload unavailable")
        val resultLimit = limit.coerceIn(1, 24)

        // Collect each kind independently so that early video results do not prevent Shorts
        // or channel renderers farther down the page from being discovered.
        val videos = mutableListOf<ContentSearchItem>()
        val shorts = mutableListOf<ContentSearchItem>()
        val channels = mutableListOf<ContentSearchItem>()
        collectRenderers(root, "videoRenderer", videos, resultLimit, ::parseVideoRenderer)
        collectRenderers(root, "reelItemRenderer", shorts, resultLimit, ::parseReelRenderer)
        collectRenderers(root, "shortsLockupViewModel", shorts, resultLimit, ::parseShortsLockup)
        collectRenderers(root, "channelRenderer", channels, resultLimit, ::parseChannelRenderer)

        val interleaved = interleaveResults(
            videos.distinctBy { it.id },
            shorts.distinctBy { it.id },
            channels.distinctBy { it.id },
        )
        // If YouTube exposes a Short through both a video renderer and a Shorts renderer,
        // retain the Shorts identity while keeping the result's original position.
        val shortsById = shorts.distinctBy { it.id }.associateBy { it.id }
        return interleaved
            .map { item -> shortsById[item.id] ?: item }
            .distinctBy { it.id }
            .take(resultLimit)
    }

    private fun extractInitialData(html: String): JsonObject? {
        val marker = "ytInitialData ="
        val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val objectStart = html.indexOf('{', start).takeIf { it >= 0 } ?: return null
        val json = extractBalancedJson(html, objectStart) ?: return null
        return runCatching {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
    }

    private fun extractBalancedJson(source: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until source.length) {
            val ch = source[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun collectRenderers(
        value: JsonElement,
        rendererKey: String,
        results: MutableList<ContentSearchItem>,
        limit: Int,
        parse: (JsonObject) -> ContentSearchItem?,
    ) {
        if (results.size >= limit) return

        when {
            value.isJsonObject -> {
                for ((key, child) in value.asJsonObject.entrySet()) {
                    if (results.size >= limit) break
                    if (key == rendererKey && child.isJsonObject) {
                        parse(child.asJsonObject)?.let(results::add)
                    } else {
                        collectRenderers(child, rendererKey, results, limit, parse)
                    }
                }
            }
            value.isJsonArray -> {
                value.asJsonArray.forEach { child ->
                    if (results.size < limit) {
                        collectRenderers(child, rendererKey, results, limit, parse)
                    }
                }
            }
        }
    }

    private fun parseVideoRenderer(renderer: JsonObject): ContentSearchItem? {
        val id = renderer.getAsJsonPrimitive("videoId")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = firstText(renderer.getAsJsonObject("title")) ?: return null

        return ContentSearchItem(
            id = id,
            title = title,
            url = "https://www.youtube.com/watch?v=$id",
            thumbnailUrl = thumbnailUrl(renderer),
            durationLabel = firstText(renderer.getAsJsonObject("lengthText")),
            channelLabel = firstText(renderer.getAsJsonObject("ownerText"))
                ?: firstText(renderer.getAsJsonObject("longBylineText")),
            kind = ContentSearchKind.VIDEO,
        )
    }

    private fun parseReelRenderer(renderer: JsonObject): ContentSearchItem? {
        val id = renderer.getAsJsonPrimitive("videoId")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = firstText(renderer.getAsJsonObject("headline"))
            ?: firstText(renderer.getAsJsonObject("title"))
            ?: return null

        return ContentSearchItem(
            id = id,
            title = title,
            url = "https://www.youtube.com/shorts/$id",
            thumbnailUrl = thumbnailUrl(renderer),
            channelLabel = firstText(renderer.getAsJsonObject("shortBylineText"))
                ?: firstText(renderer.getAsJsonObject("ownerText")),
            kind = ContentSearchKind.SHORT,
        )
    }

    private fun parseShortsLockup(renderer: JsonObject): ContentSearchItem? {
        val commandId = renderer.getAsJsonObject("onTap")
            ?.getAsJsonObject("innertubeCommand")
            ?.getAsJsonObject("reelWatchEndpoint")
            ?.getAsJsonPrimitive("videoId")
            ?.asString
            ?.takeIf { it.isNotBlank() }
        val entityId = renderer.getAsJsonPrimitive("entityId")?.asString
        val id = commandId ?: entityId
            ?.takeIf { it.startsWith("shorts-shelf-item-") }
            ?.removePrefix("shorts-shelf-item-")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = firstText(
            renderer.getAsJsonObject("overlayMetadata")?.getAsJsonObject("primaryText"),
        ) ?: firstText(renderer.getAsJsonObject("headline")) ?: return null

        return ContentSearchItem(
            id = id,
            title = title,
            url = "https://www.youtube.com/shorts/$id",
            thumbnailUrl = thumbnailUrl(renderer),
            kind = ContentSearchKind.SHORT,
        )
    }

    private fun parseChannelRenderer(renderer: JsonObject): ContentSearchItem? {
        val id = renderer.getAsJsonPrimitive("channelId")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: renderer.getAsJsonObject("navigationEndpoint")
                ?.getAsJsonObject("browseEndpoint")
                ?.getAsJsonPrimitive("browseId")
                ?.asString
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = firstText(renderer.getAsJsonObject("title")) ?: return null

        return ContentSearchItem(
            id = id,
            title = title,
            url = "https://www.youtube.com/channel/$id",
            thumbnailUrl = thumbnailUrl(renderer),
            subtitle = firstText(renderer.getAsJsonObject("subscriberCountText"))
                ?: firstText(renderer.getAsJsonObject("videoCountText")),
            kind = ContentSearchKind.CHANNEL,
        )
    }

    private fun thumbnailUrl(renderer: JsonObject): String? {
        val thumbnail = renderer.getAsJsonObject("thumbnail") ?: return null
        val url = thumbnail.getAsJsonArray("thumbnails")?.lastOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.getAsJsonPrimitive("url")
            ?.asString
            ?: thumbnail.getAsJsonArray("sources")?.lastOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonPrimitive("url")
                ?.asString
            ?: return null

        return url.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
    }

    private fun firstText(node: JsonObject?): String? {
        if (node == null) return null
        node.getAsJsonPrimitive("simpleText")?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        node.getAsJsonPrimitive("content")?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val runs: JsonArray = node.getAsJsonArray("runs") ?: return null
        return runs
            .mapNotNull { run ->
                run.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.getAsJsonPrimitive("text")
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
            }
            .joinToString("")
            .takeIf { it.isNotBlank() }
    }

    private fun interleaveResults(
        vararg groups: List<ContentSearchItem>,
    ): List<ContentSearchItem> {
        val output = mutableListOf<ContentSearchItem>()
        var index = 0
        while (groups.any { index < it.size }) {
            groups.forEach { group ->
                group.getOrNull(index)?.let(output::add)
            }
            index++
        }
        return output
    }
}
