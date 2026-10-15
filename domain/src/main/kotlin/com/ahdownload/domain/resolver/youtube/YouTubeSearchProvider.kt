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
import java.util.UUID

class YouTubeSearchProvider(
    private val httpClient: HttpTextClient,
) : ContentSearchProvider {
    override suspend fun search(query: String, limit: Int): List<ContentSearchItem> =
        search(query, limit, emptyMap())

    override suspend fun search(
        query: String,
        limit: Int,
        diagnosticContext: Map<String, String>,
    ): List<ContentSearchItem> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotBlank()) { "Search query must not be blank" }

        val encoded = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8.toString())
        // Never log the query text itself. Keep only a length and a stable operation ID so
        // transport failures can be correlated with the user-visible search failure.
        val operationId = diagnosticContext["operation_id"]
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val requestDiagnosticContext = diagnosticContext + mapOf(
            "diagnostic_operation" to "youtube.search",
            "operation_id" to operationId,
            "platform" to "YouTube",
            "stage" to "SEARCH",
            "search_query_length" to normalizedQuery.length.toString(),
        )
        val html = httpClient.get(
            "https://www.youtube.com/results?search_query=$encoded",
            headers = mapOf(
                "Accept-Language" to "en-US,en;q=0.9",
            ),
            diagnosticContext = requestDiagnosticContext,
        )

        val root = extractInitialData(html)
            ?: throw IllegalStateException("YouTube search payload unavailable")
        val resultLimit = limit.coerceIn(1, 24)

        // Traverse the payload once to preserve YouTube's source ordering. If a video is
        // later identified as a Short by a Shorts renderer, upgrade its kind in place.
        val ordered = mutableListOf<ContentSearchItem>()
        val indexById = mutableMapOf<String, Int>()
        collectRenderersInSourceOrder(root) { item ->
            val existingIndex = indexById[item.id]
            if (existingIndex == null) {
                indexById[item.id] = ordered.size
                ordered += item
            } else if (
                item.kind == ContentSearchKind.SHORT &&
                ordered[existingIndex].kind != ContentSearchKind.SHORT
            ) {
                val existing = ordered[existingIndex]
                ordered[existingIndex] = item.copy(
                    thumbnailUrl = item.thumbnailUrl ?: existing.thumbnailUrl,
                    durationLabel = item.durationLabel ?: existing.durationLabel,
                    channelLabel = item.channelLabel ?: existing.channelLabel,
                    subtitle = item.subtitle ?: existing.subtitle,
                )
            }
        }
        return ordered.take(resultLimit)
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

    private fun collectRenderersInSourceOrder(
        value: JsonElement,
        onResult: (ContentSearchItem) -> Unit,
    ) {
        when {
            value.isJsonObject -> {
                for ((key, child) in value.asJsonObject.entrySet()) {
                    if (child.isJsonObject) {
                        val renderer = child.asJsonObject
                        val parsed = when (key) {
                            "videoRenderer" -> parseVideoRenderer(renderer)
                            "reelItemRenderer" -> parseReelRenderer(renderer)
                            "shortsLockupViewModel" -> parseShortsLockup(renderer)
                            "channelRenderer" -> parseChannelRenderer(renderer)
                            else -> null
                        }
                        parsed?.let(onResult)
                    }
                    collectRenderersInSourceOrder(child, onResult)
                }
            }
            value.isJsonArray -> {
                value.asJsonArray.forEach { child ->
                    collectRenderersInSourceOrder(child, onResult)
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

}
