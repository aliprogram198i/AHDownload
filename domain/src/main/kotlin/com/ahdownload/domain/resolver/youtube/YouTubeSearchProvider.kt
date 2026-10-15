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
            ?: throw createPayloadUnavailableException(html)
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

    /**
     * YouTube has served the same public search payload through several JavaScript assignment
     * forms. Match the assignment explicitly and parse only its balanced JSON object; never
     * scrape arbitrary later braces from a consent, challenge, or error page.
     */
    private fun extractInitialData(html: String): JsonObject? {
        for (assignment in INITIAL_DATA_ASSIGNMENT.findAll(html)) {
            val valueStart = skipWhitespace(html, assignment.range.last + 1)
            val json = extractAssignedJson(html, valueStart) ?: continue
            val parsed = runCatching {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun extractAssignedJson(source: String, start: Int): String? {
        if (source.regionMatches(start, "JSON.parse", 0, "JSON.parse".length)) {
            var position = skipWhitespace(source, start + "JSON.parse".length)
            if (position >= source.length || source[position] != '(') return null
            position = skipWhitespace(source, position + 1)
            val literalEnd = findQuotedStringEnd(source, position) ?: return null
            val literal = source.substring(position, literalEnd + 1)
            val decoded = decodeQuotedStringLiteral(literal) ?: return null
            val objectStart = skipWhitespace(decoded, 0)
            if (objectStart >= decoded.length || decoded[objectStart] != '{') return null
            return extractBalancedJson(decoded, objectStart)
        }

        if (start >= source.length || source[start] != '{') return null
        return extractBalancedJson(source, start)
    }

    private fun skipWhitespace(source: String, from: Int): Int {
        var position = from.coerceAtLeast(0)
        while (position < source.length && source[position].isWhitespace()) position++
        return position
    }

    private fun findQuotedStringEnd(source: String, start: Int): Int? {
        if (start >= source.length || source[start] !in charArrayOf('"', '\'')) return null
        val quote = source[start]
        var escaped = false
        for (index in start + 1 until source.length) {
            val ch = source[index]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == quote) {
                return index
            }
        }
        return null
    }

    private fun decodeQuotedStringLiteral(literal: String): String? = runCatching {
        if (literal.firstOrNull() == '"') {
            JsonParser.parseString(literal).asString
        } else {
            // JSON.parse may be called with a JavaScript single-quoted string. Convert its
            // quoting rules into a valid JSON string before decoding, without evaluating JS.
            val body = literal.substring(1, literal.length - 1)
            val normalized = buildString {
                append('"')
                var index = 0
                while (index < body.length) {
                    val ch = body[index]
                    if (ch == '\\' && index + 1 < body.length) {
                        val next = body[index + 1]
                        when (next) {
                            '\'' -> append('\'')
                            '"' -> append("\\\"")
                            else -> {
                                append('\\')
                                append(next)
                            }
                        }
                        index += 2
                    } else {
                        if (ch == '"') append("\\\"") else append(ch)
                        index++
                    }
                }
                append('"')
            }
            JsonParser.parseString(normalized).asString
        }
    }.getOrNull()

    private fun createPayloadUnavailableException(html: String): YouTubeSearchPayloadException {
        val sample = html.take(MAX_DIAGNOSTIC_SAMPLE_CHARS).lowercase()
        val markerPresent = INITIAL_DATA_ASSIGNMENT.containsMatchIn(html)
        val reason = when {
            html.isBlank() -> "empty_response"
            listOf(
                "unusual traffic from your computer network",
                "automated queries",
                "verify you're not a robot",
                "verify you are not a robot",
                "our systems have detected unusual traffic",
                "captcha",
            ).any { it in sample } -> "bot_challenge"
            listOf(
                "before you continue to youtube",
                "consent.youtube.com",
                "consent.google.com",
                "we use cookies and data",
                "manage your privacy settings",
            ).any { it in sample } -> "consent_or_privacy_gate"
            markerPresent || "ytinitialdata" in sample -> "initial_data_parse_failed"
            else -> "initial_data_marker_missing"
        }
        return YouTubeSearchPayloadException(
            payloadFailureReason = reason,
            responseChars = html.length,
            initialDataAssignmentPresent = markerPresent,
        )
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

    private companion object {
        val INITIAL_DATA_ASSIGNMENT = Regex(
            """(?:window\\s*\\[\\s*["']ytInitialData["']\\s*]|window\\s*\\.\\s*ytInitialData|\\b(?:var|let|const)\\s+ytInitialData\\b|\\bytInitialData\\b)\\s*=\\s*""",
        )
        const val MAX_DIAGNOSTIC_SAMPLE_CHARS = 250_000
    }

}

/**
 * Safe, redacted details for an HTTP-200 YouTube response whose search data could not be parsed.
 * The response body itself is intentionally never retained in the exception.
 */
class YouTubeSearchPayloadException(
    val payloadFailureReason: String,
    val responseChars: Int,
    val initialDataAssignmentPresent: Boolean,
) : IllegalStateException("YouTube search payload unavailable")
