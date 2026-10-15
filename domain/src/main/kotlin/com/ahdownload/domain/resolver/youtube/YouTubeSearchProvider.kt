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

        val extraction = extractInitialData(html)
        val root = extraction.payload
            ?: throw createPayloadUnavailableException(html, extraction)
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
     * YouTube has served ytInitialData as a direct object, a parenthesized object, or a
     * JSON.parse call. Decode only quoted string expressions; never evaluate page JavaScript.
     */
    private fun extractInitialData(html: String): InitialDataExtraction {
        var assignmentCount = 0
        var lastValueKind = "unknown"
        var lastFailureStage = "assignment_not_found"

        for (assignment in INITIAL_DATA_ASSIGNMENT.findAll(html)) {
            assignmentCount++
            val valueStart = skipJavaScriptTrivia(html, assignment.range.last + 1)
            lastValueKind = classifyAssignmentValue(html, valueStart)
            val json = extractAssignedJson(html, valueStart)
            if (json == null) {
                lastFailureStage = "expression_decode"
                continue
            }

            val parsed = runCatching {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull()
            if (parsed == null) {
                lastFailureStage = "json_syntax"
                continue
            }
            if (!looksLikeInitialData(parsed)) {
                lastFailureStage = "root_shape"
                continue
            }

            return InitialDataExtraction(
                payload = parsed,
                assignmentCount = assignmentCount,
                assignmentValueKind = lastValueKind,
                failureStage = "success",
            )
        }

        return InitialDataExtraction(
            payload = null,
            assignmentCount = assignmentCount,
            assignmentValueKind = lastValueKind,
            failureStage = lastFailureStage,
        )
    }

    private fun looksLikeInitialData(root: JsonObject): Boolean =
        root.keySet().any { it in INITIAL_DATA_ROOT_KEYS }

    private fun classifyAssignmentValue(source: String, start: Int): String {
        val position = skipJavaScriptTrivia(source, start)
        return when {
            position >= source.length -> "empty"
            source[position] == '{' -> "object"
            source[position] == '(' -> "parenthesized_expression"
            source.regionMatches(position, "JSON.parse", 0, "JSON.parse".length) &&
                source.indexOf("decodeURIComponent", position) >= 0 ->
                "json_parse_decode_uri"
            source.regionMatches(position, "JSON.parse", 0, "JSON.parse".length) &&
                source.indexOf("atob", position) >= 0 ->
                "json_parse_base64"
            source.regionMatches(position, "JSON.parse", 0, "JSON.parse".length) ->
                "json_parse"
            source.regionMatches(position, "Object.freeze", 0, "Object.freeze".length) ->
                "object_freeze"
            source[position] == '"' || source[position] == '\'' || source[position] == '\u0060' ->
                "string_literal"
            else -> "unsupported_expression"
        }
    }

    private fun extractAssignedJson(source: String, start: Int, depth: Int = 0): String? {
        if (depth > MAX_EXPRESSION_UNWRAP_DEPTH) return null
        val position = skipJavaScriptTrivia(source, start)
        if (position >= source.length) return null

        val jsonParseArgument = callArgumentStart(source, position, "JSON.parse")
        if (jsonParseArgument != null) {
            val decoded = decodeJavaScriptStringExpression(source, jsonParseArgument) ?: return null
            return extractJsonObjectText(decoded)
        }

        val frozenObjectArgument = callArgumentStart(source, position, "Object.freeze")
        if (frozenObjectArgument != null) {
            return extractAssignedJson(source, frozenObjectArgument, depth + 1)
        }

        if (source[position] == '(') {
            return extractAssignedJson(source, position + 1, depth + 1)
        }

        if (source[position] == '{') {
            return extractBalancedJson(source, position)
        }

        return null
    }

    private fun callArgumentStart(source: String, start: Int, functionName: String): Int? {
        if (!source.regionMatches(start, functionName, 0, functionName.length)) return null
        val nameEnd = start + functionName.length
        if (nameEnd < source.length &&
            (source[nameEnd].isLetterOrDigit() || source[nameEnd] == '_')
        ) return null

        val openParen = skipJavaScriptTrivia(source, nameEnd)
        if (openParen >= source.length || source[openParen] != '(') return null
        return skipJavaScriptTrivia(source, openParen + 1)
    }

    /**
     * Decode a JavaScript string or the narrowly supported string wrappers used by JSON.parse.
     * No expression evaluation is performed. Unsupported wrappers fail closed.
     */
    private fun decodeJavaScriptStringExpression(source: String, start: Int): String? {
        val position = skipJavaScriptTrivia(source, start)
        if (position >= source.length) return null

        val quote = source[position]
        if (quote == '"' || quote == '\'' || quote == '\u0060') {
            val end = findQuotedStringEnd(source, position) ?: return null
            return decodeJavaScriptStringLiteral(source.substring(position, end + 1))
        }

        for (wrapper in listOf("decodeURIComponent", "atob")) {
            val argumentStart = callArgumentStart(source, position, wrapper) ?: continue
            if (argumentStart >= source.length) return null
            val argumentQuote = source[argumentStart]
            if (argumentQuote != '"' && argumentQuote != '\'' && argumentQuote != '\u0060') return null
            val argumentEnd = findQuotedStringEnd(source, argumentStart) ?: return null
            val closeParen = skipJavaScriptTrivia(source, argumentEnd + 1)
            if (closeParen >= source.length || source[closeParen] != ')') return null
            val encoded = decodeJavaScriptStringLiteral(source.substring(argumentStart, argumentEnd + 1))
                ?: return null
            return runCatching {
                when (wrapper) {
                    "decodeURIComponent" -> {
                        // Java URLDecoder treats '+' as a space; JavaScript decodeURIComponent does not.
                        java.net.URLDecoder.decode(
                            encoded.replace("+", "%2B"),
                            StandardCharsets.UTF_8.name(),
                        )
                    }
                    "atob" -> String(
                        java.util.Base64.getDecoder().decode(encoded),
                        StandardCharsets.UTF_8,
                    )
                    else -> null
                }
            }.getOrNull()
        }

        return null
    }

    private fun decodeJavaScriptStringLiteral(literal: String): String? = runCatching {
        if (literal.length < 2) return null
        val quote = literal.first()
        if (literal.last() != quote || quote !in charArrayOf('"', '\'', '\u0060')) return null
        val body = literal.substring(1, literal.length - 1)
        if (quote == '\u0060' && body.contains("\${")) return null

        buildString {
            var index = 0
            while (index < body.length) {
                val ch = body[index]
                if (ch != '\\') {
                    if (quote != '\u0060' && (ch == '\n' || ch == '\r')) return null
                    append(ch)
                    index++
                    continue
                }

                index++
                if (index >= body.length) return null
                when (val escaped = body[index]) {
                    '\n' -> index++
                    '\r' -> {
                        index++
                        if (index < body.length && body[index] == '\n') index++
                    }
                    'b' -> { append('\b'); index++ }
                    'f' -> { append('\u000C'); index++ }
                    'n' -> { append('\n'); index++ }
                    'r' -> { append('\r'); index++ }
                    't' -> { append('\t'); index++ }
                    'v' -> { append('\u000B'); index++ }
                    '0' -> {
                        if (index + 1 < body.length && body[index + 1].isDigit()) return null
                        append('\u0000')
                        index++
                    }
                    'x' -> {
                        if (index + 2 >= body.length) return null
                        val code = body.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                        append(code.toChar())
                        index += 3
                    }
                    'u' -> {
                        if (index + 1 < body.length && body[index + 1] == '{') {
                            val close = body.indexOf('}', index + 2)
                            if (close < 0) return null
                            val codePoint = body.substring(index + 2, close).toIntOrNull(16) ?: return null
                            if (!Character.isValidCodePoint(codePoint)) return null
                            append(String(Character.toChars(codePoint)))
                            index = close + 1
                        } else {
                            if (index + 4 >= body.length) return null
                            val code = body.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                            append(code.toChar())
                            index += 5
                        }
                    }
                    else -> {
                        // JavaScript permits escaped quotes, backslashes, and identity escapes.
                        append(escaped)
                        index++
                    }
                }
            }
        }
    }.getOrNull()

    private fun findQuotedStringEnd(source: String, start: Int): Int? {
        if (start >= source.length || source[start] !in charArrayOf('"', '\'', '\u0060')) return null
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

    private fun skipJavaScriptTrivia(source: String, from: Int): Int {
        var position = from.coerceAtLeast(0)
        while (position < source.length) {
            if (source[position].isWhitespace()) {
                position++
                continue
            }
            if (source.startsWith("//", position)) {
                val newline = source.indexOf('\n', position + 2)
                position = if (newline < 0) source.length else newline + 1
                continue
            }
            if (source.startsWith("/*", position)) {
                val close = source.indexOf("*/", position + 2)
                position = if (close < 0) source.length else close + 2
                continue
            }
            break
        }
        return position
    }

    private fun skipWhitespace(source: String, from: Int): Int {
        var position = from.coerceAtLeast(0)
        while (position < source.length && source[position].isWhitespace()) position++
        return position
    }

    private fun extractJsonObjectText(decoded: String): String? {
        val source = decoded.removePrefix("\uFEFF")
        var start = skipWhitespace(source, 0)
        if (source.startsWith(")]}'", start)) {
            val newline = source.indexOf('\n', start + 4)
            if (newline < 0) return null
            start = skipWhitespace(source, newline + 1)
        }
        if (start >= source.length || source[start] != '{') return null
        return extractBalancedJson(source, start)
    }

    private fun createPayloadUnavailableException(
        html: String,
        extraction: InitialDataExtraction,
    ): YouTubeSearchPayloadException {
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
            assignmentCount = extraction.assignmentCount,
            assignmentValueKind = extraction.assignmentValueKind,
            parserFailureStage = extraction.failureStage,
        )
    }

    private data class InitialDataExtraction(
        val payload: JsonObject?,
        val assignmentCount: Int,
        val assignmentValueKind: String,
        val failureStage: String,
    )

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
            """(?:window\s*\[\s*["']ytInitialData["']\s*]|window\s*\.\s*ytInitialData|\b(?:var|let|const)\s+ytInitialData\b|\bytInitialData\b)\s*=\s*""",
        )
        val INITIAL_DATA_ROOT_KEYS = setOf(
            "contents",
            "responseContext",
            "metadata",
            "header",
            "topbar",
            "engagementPanels",
            "alerts",
            "page",
            "microformat",
            "onResponseReceivedCommands",
            "onResponseReceivedEndpoints",
            "frameworkUpdates",
        )
        const val MAX_DIAGNOSTIC_SAMPLE_CHARS = 250_000
        const val MAX_EXPRESSION_UNWRAP_DEPTH = 4
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
    val assignmentCount: Int,
    val assignmentValueKind: String,
    val parserFailureStage: String,
) : IllegalStateException("YouTube search payload unavailable")
