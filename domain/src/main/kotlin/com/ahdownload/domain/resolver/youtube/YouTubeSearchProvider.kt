package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.search.ContentSearchItem
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

        val results = mutableListOf<ContentSearchItem>()
        collectVideoRenderers(root, results, limit.coerceIn(1, 24))
        return results.distinctBy { it.id }.take(limit.coerceIn(1, 24))
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

    private fun collectVideoRenderers(
        value: JsonElement,
        results: MutableList<ContentSearchItem>,
        limit: Int,
    ) {
        if (results.size >= limit) return

        when {
            value.isJsonObject -> {
                val jsonObject = value.asJsonObject
                for ((key, child) in jsonObject.entrySet()) {
                    if (results.size >= limit) break
                    if (key == "videoRenderer" && child.isJsonObject) {
                        parseRenderer(child.asJsonObject)?.let(results::add)
                    } else {
                        collectVideoRenderers(child, results, limit)
                    }
                }
            }
            value.isJsonArray -> {
                value.asJsonArray.forEach { child ->
                    if (results.size < limit) {
                        collectVideoRenderers(child, results, limit)
                    }
                }
            }
        }
    }

    private fun parseRenderer(renderer: JsonObject): ContentSearchItem? {
        val id = renderer.getAsJsonPrimitive("videoId")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val title = firstText(renderer.getAsJsonObject("title")) ?: return null
        val thumbnailUrl = renderer
            .getAsJsonObject("thumbnail")
            ?.getAsJsonArray("thumbnails")
            ?.lastOrNull()
            ?.asJsonObject
            ?.getAsJsonPrimitive("url")
            ?.asString
            ?.takeIf { it.isNotBlank() }

        return ContentSearchItem(
            id = id,
            title = title,
            url = "https://www.youtube.com/watch?v=$id",
            thumbnailUrl = thumbnailUrl,
            durationLabel = firstText(renderer.getAsJsonObject("lengthText")),
            channelLabel = firstText(renderer.getAsJsonObject("ownerText")),
        )
    }

    private fun firstText(node: JsonObject?): String? {
        if (node == null) return null
        node.getAsJsonPrimitive("simpleText")
            ?.asString
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
