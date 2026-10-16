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
import kotlinx.coroutines.CancellationException

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
        val resultLimit = limit.coerceIn(1, 24)
        val pageResults = extraction.payload?.let { collectSearchResults(it, resultLimit) }.orEmpty()
        if (pageResults.isNotEmpty()) return pageResults

        // The HTML boot payload is not a stable search API. If YouTube changes its
        // ytInitialData wrapper or ships a shell page, retry through the first-party
        // Innertube search endpoint using the API key/context embedded in that same
        // response. This path is only used when the page parser yields no results.
        val fallback = fetchInnertubeSearch(
            html = html,
            query = normalizedQuery,
            diagnosticContext = requestDiagnosticContext,
        )
        val fallbackResults = fallback.payload?.let { collectSearchResults(it, resultLimit) }
        if (!fallbackResults.isNullOrEmpty()) return fallbackResults
        if (fallback.payload != null) return emptyList()

        throw createPayloadUnavailableException(html, extraction, fallback)
    }

    private fun collectSearchResults(root: JsonObject, resultLimit: Int): List<ContentSearchItem> {
        // Preserve source ordering and merge duplicate video/Short renderers without showing
        // the same video twice. Both the HTML payload and the Innertube response use this path.
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

    private suspend fun fetchInnertubeSearch(
        html: String,
        query: String,
        diagnosticContext: Map<String, String>,
    ): InnertubeSearchFallback {
        val apiKey = extractConfiguredString(html, "INNERTUBE_API_KEY")
            ?: return InnertubeSearchFallback(
                payload = null,
                outcome = "skipped",
                failureDetail = "api_key_missing",
                attempted = false,
            )
        val context = extractConfiguredObject(html, "INNERTUBE_CONTEXT")
        val clientVersion = context
            ?.getAsJsonObject("client")
            ?.getAsJsonPrimitive("clientVersion")
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: extractConfiguredString(html, "INNERTUBE_CONTEXT_CLIENT_VERSION")
            ?: extractConfiguredString(html, "INNERTUBE_CLIENT_VERSION")
        if (clientVersion.isNullOrBlank()) {
            return InnertubeSearchFallback(
                payload = null,
                outcome = "skipped",
                failureDetail = "client_version_missing",
                attempted = false,
            )
        }

        val requestContext = context?.deepCopy() ?: JsonObject().apply {
            add("client", JsonObject().apply {
                addProperty("hl", "en")
                addProperty("gl", "US")
                addProperty("clientName", "WEB")
                addProperty("clientVersion", clientVersion)
            })
        }
        val client = requestContext.getAsJsonObject("client")
            ?: JsonObject().also { requestContext.add("client", it) }
        if (client.get("clientName") == null) client.addProperty("clientName", "WEB")
        if (client.get("clientVersion") == null) client.addProperty("clientVersion", clientVersion)
        val clientName = client.getAsJsonPrimitive("clientName")?.asString ?: "WEB"
        val clientNameId = extractConfiguredString(html, "INNERTUBE_CONTEXT_CLIENT_NAME")
            ?: extractConfiguredString(html, "INNERTUBE_CLIENT_NAME")
            ?: when (clientName) {
                "WEB" -> "1"
                "ANDROID" -> "3"
                "IOS" -> "5"
                "WEB_EMBEDDED_PLAYER" -> "56"
                else -> "1"
            }

        val body = JsonObject().apply {
            add("context", requestContext)
            addProperty("query", query)
        }
        val headers = buildMap {
            put("Accept", "application/json")
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            put("X-YouTube-Client-Name", clientNameId)
            put("X-YouTube-Client-Version", clientVersion)
            client.getAsJsonPrimitive("visitorData")?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { put("X-Goog-Visitor-Id", it) }
        }
        val endpoint = "https://www.youtube.com/youtubei/v1/search?key=" +
            URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        val fallbackContext = diagnosticContext + mapOf(
            "diagnostic_operation" to "youtube.search.innertube",
            "search_strategy" to "INNERTUBE_FALLBACK",
            "request_payload_type" to "search_query_and_client_context",
        )

        val raw = try {
            httpClient.postJson(endpoint, body.toString(), headers, fallbackContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val detail = when {
                error.message?.contains("HTTP 403", ignoreCase = true) == true -> "http_403"
                error.message?.contains("HTTP 429", ignoreCase = true) == true -> "http_429"
                error.message?.contains("HTTP 400", ignoreCase = true) == true -> "http_400"
                error.message?.contains("HTTP 5", ignoreCase = true) == true -> "http_5xx"
                error is java.net.SocketTimeoutException -> "timeout"
                error is java.net.UnknownHostException -> "dns_failure"
                error is java.io.IOException -> "io_failure"
                else -> "request_failed"
            }
            return InnertubeSearchFallback(
                payload = null,
                outcome = "failed",
                failureDetail = detail,
                attempted = true,
            )
        }

        val parsed = runCatching {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
        if (parsed == null) {
            return InnertubeSearchFallback(
                payload = null,
                outcome = "failed",
                failureDetail = "response_json_invalid",
                attempted = true,
            )
        }
        if (!looksLikeInitialData(parsed)) {
            return InnertubeSearchFallback(
                payload = null,
                outcome = "failed",
                failureDetail = "response_shape_unexpected",
                attempted = true,
            )
        }
        return InnertubeSearchFallback(
            payload = parsed,
            outcome = "success",
            failureDetail = "none",
            attempted = true,
        )
    }

    private fun extractConfiguredString(source: String, name: String): String? {
        val markers = listOf(
            "\"${name}\":\"",
            "\"${name}\": \"",
            "${name} = \"",
            "${name}=\"",
        )
        for (marker in markers) {
            val markerStart = source.indexOf(marker)
            if (markerStart < 0) continue
            val valueStart = markerStart + marker.length
            val valueEnd = source.indexOf('"', valueStart)
            if (valueEnd > valueStart) {
                return source.substring(valueStart, valueEnd).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun extractConfiguredObject(source: String, name: String): JsonObject? {
        val markers = listOf("\"${name}\":", "\"${name}\": ", "${name} = ", "${name}=")
        for (marker in markers) {
            val markerStart = source.indexOf(marker)
            if (markerStart < 0) continue
            var valueStart = skipWhitespace(source, markerStart + marker.length)
            if (valueStart >= source.length || source[valueStart] != '{') continue
            val json = extractBalancedJson(source, valueStart) ?: continue
            val parsed = runCatching {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private data class InnertubeSearchFallback(
        val payload: JsonObject?,
        val outcome: String,
        val failureDetail: String,
        val attempted: Boolean,
    )

    /**
     * YouTube has served ytInitialData as a direct object, a parenthesized object, or a
     * JSON.parse call. Decode only quoted string expressions; never evaluate page JavaScript.
     */
    private fun extractInitialData(html: String): InitialDataExtraction {
        var assignmentCount = 0
        var lastValueKind = "unknown"
        var lastFailureStage = "assignment_not_found"
        var lastExpressionDiagnostics = PayloadExpressionDiagnostics()

        for (assignment in INITIAL_DATA_ASSIGNMENT.findAll(html)) {
            assignmentCount++
            val valueStart = skipJavaScriptTrivia(html, assignment.range.last + 1)
            lastValueKind = classifyAssignmentValue(html, valueStart)
            val json = extractAssignedJson(html, valueStart)
            if (json == null) {
                lastFailureStage = "expression_decode"
                lastExpressionDiagnostics = diagnosePayloadExpression(
                    html, valueStart, assignment.range.first, assignmentCount, lastFailureStage,
                )
                continue
            }

            val parsed = runCatching {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull()
            if (parsed == null) {
                lastFailureStage = "json_syntax"
                lastExpressionDiagnostics = diagnosePayloadExpression(
                    html, valueStart, assignment.range.first, assignmentCount, lastFailureStage,
                )
                continue
            }
            if (!looksLikeInitialData(parsed)) {
                lastFailureStage = "root_shape"
                lastExpressionDiagnostics = diagnosePayloadExpression(
                    html, valueStart, assignment.range.first, assignmentCount, lastFailureStage,
                )
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
            expressionDiagnostics = lastExpressionDiagnostics,
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
            isJsonParseWrapper(source, position, "decodeURIComponent") -> "json_parse_decode_uri"
            isJsonParseWrapper(source, position, "atob") -> "json_parse_base64"
            callArgumentStart(source, position, "JSON.parse") != null -> "json_parse"
            callArgumentStart(source, position, "Object.freeze") != null -> "object_freeze"
            source[position] == '"' || source[position] == '\'' || source[position] == '\u0060' ->
                "string_literal"
            else -> "unsupported_expression"
        }
    }

    private fun isJsonParseWrapper(source: String, start: Int, wrapper: String): Boolean {
        val argument = callArgumentStart(source, start, "JSON.parse") ?: return false
        return callArgumentStart(source, skipJavaScriptTrivia(source, argument), wrapper) != null
    }

    private fun diagnosePayloadExpression(
        source: String,
        expressionStart: Int,
        assignmentOffset: Int,
        assignmentAttempt: Int,
        failureStage: String,
    ): PayloadExpressionDiagnostics {
        val position = skipJavaScriptTrivia(source, expressionStart)
        val argumentStart = callArgumentStart(source, position, "JSON.parse")
        val wrapper = when {
            argumentStart != null -> "json_parse"
            callArgumentStart(source, position, "Object.freeze") != null -> "object_freeze"
            position < source.length && source[position] == '(' -> "parenthesized"
            position < source.length && source[position] == '{' -> "direct_object"
            else -> "none"
        }

        if (argumentStart == null) {
            val end = scanAssignmentExpressionEnd(source, position)
            return PayloadExpressionDiagnostics(
                wrapper = wrapper,
                kind = classifyExpressionKind(source, position, end.endExclusive),
                resolution = "not_applicable",
                failureDetail = when (failureStage) {
                    "json_syntax" -> "decoded_json_syntax_invalid"
                    "root_shape" -> "decoded_json_root_shape_unexpected"
                    else -> "unsupported_expression_shape"
                },
                expressionLength = (end.endExclusive - position).coerceAtLeast(0),
                expressionStartOffset = position,
                assignmentOffset = assignmentOffset,
                assignmentAttempt = assignmentAttempt,
                scriptIndex = scriptIndexAt(source, assignmentOffset),
                argumentCount = 0,
                argumentTermination = end.termination,
            )
        }

        val normalizedArgumentStart = skipJavaScriptTrivia(source, argumentStart)
        val span = findCallArgumentEnd(source, normalizedArgumentStart)
        val kind = classifyExpressionKind(source, normalizedArgumentStart, span.endExclusive)
        val identifierEnd = readJavaScriptIdentifierEnd(source, normalizedArgumentStart)
        val resolution = if (kind == "identifier_reference" && identifierEnd != null) {
            diagnoseStaticIdentifierResolution(
                source,
                source.substring(normalizedArgumentStart, identifierEnd),
                normalizedArgumentStart,
            )
        } else "not_applicable"
        val detail = when (failureStage) {
            "json_syntax" -> "decoded_json_syntax_invalid"
            "root_shape" -> "decoded_json_root_shape_unexpected"
            else -> when {
                span.termination == "comma" &&
                    !hasSupportedNullSecondArgument(source, span.endExclusive) ->
                    "unsupported_json_parse_second_argument"
                span.termination == "unclosed_call" -> "json_parse_call_unterminated"
                kind == "identifier_reference" -> when (resolution) {
                    "static_string_declaration_resolvable" ->
                        "static_identifier_suffix_or_delimiter_unsupported"
                    "same_script_initializer_not_static_string" ->
                        "static_identifier_initializer_unsupported"
                    "declaration_outside_current_script" ->
                        "static_identifier_declared_in_other_script"
                    else -> "unresolved_static_identifier"
                }
                kind == "nested_function_call" -> "unsupported_nested_function_call"
                kind == "member_reference" || kind == "computed_reference" ->
                    "unsupported_member_expression"
                kind == "dynamic_template_literal" -> "dynamic_template_literal_unsupported"
                kind == "string_literal" || kind == "static_template_literal" ->
                    "quoted_string_decode_failed_or_unexpected_suffix"
                else -> "unsupported_expression_shape"
            }
        }
        return PayloadExpressionDiagnostics(
            wrapper = wrapper,
            kind = kind,
            resolution = resolution,
            failureDetail = detail,
            expressionLength = (span.endExclusive - normalizedArgumentStart).coerceAtLeast(0),
            expressionStartOffset = normalizedArgumentStart,
            assignmentOffset = assignmentOffset,
            assignmentAttempt = assignmentAttempt,
            scriptIndex = scriptIndexAt(source, assignmentOffset),
            argumentCount = if (span.termination == "comma") 2
                else if (span.termination == "closing_paren") 1 else 0,
            argumentTermination = span.termination,
        )
    }

    private data class ExpressionSpan(val endExclusive: Int, val termination: String)

    private fun findCallArgumentEnd(source: String, start: Int): ExpressionSpan {
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var quote: Char? = null
        var escaped = false
        var index = start
        while (index < source.length) {
            val ch = source[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = null
                index++
                continue
            }
            if (source.startsWith("//", index)) {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline < 0) source.length else newline + 1
                continue
            }
            if (source.startsWith("/*", index)) {
                val close = source.indexOf("*/", index + 2)
                index = if (close < 0) source.length else close + 2
                continue
            }
            when (ch) {
                '"', '\'', '\u0060' -> quote = ch
                '(' -> parentheses++
                ')' -> {
                    if (parentheses == 0 && brackets == 0 && braces == 0) {
                        return ExpressionSpan(index, "closing_paren")
                    }
                    if (parentheses > 0) parentheses--
                }
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> braces++
                '}' -> if (braces > 0) braces--
                ',' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    return ExpressionSpan(index, "comma")
                }
            }
            index++
        }
        return ExpressionSpan(source.length, "unclosed_call")
    }

    private fun hasSupportedNullSecondArgument(source: String, commaOffset: Int): Boolean {
        if (commaOffset >= source.length || source[commaOffset] != ',') return false
        val valueStart = skipJavaScriptTrivia(source, commaOffset + 1)
        if (!source.regionMatches(valueStart, "null", 0, 4)) return false
        val valueEnd = valueStart + 4
        if (valueEnd < source.length &&
            (source[valueEnd].isLetterOrDigit() || source[valueEnd] == '_' || source[valueEnd] == '$')
        ) return false
        val closing = skipJavaScriptTrivia(source, valueEnd)
        return closing < source.length && source[closing] == ')'
    }

    private fun scanAssignmentExpressionEnd(source: String, start: Int): ExpressionSpan {
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var quote: Char? = null
        var escaped = false
        var index = start
        while (index < source.length) {
            val ch = source[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = null
                index++
                continue
            }
            if (source.startsWith("//", index)) {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline < 0) source.length else newline + 1
                continue
            }
            if (source.startsWith("/*", index)) {
                val close = source.indexOf("*/", index + 2)
                index = if (close < 0) source.length else close + 2
                continue
            }
            when (ch) {
                '"', '\'', '\u0060' -> quote = ch
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses-- else return ExpressionSpan(index, "closing_paren")
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> braces++
                '}' -> if (braces > 0) braces-- else return ExpressionSpan(index, "closing_brace")
                ';' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    return ExpressionSpan(index, "semicolon")
                }
            }
            index++
        }
        return ExpressionSpan(source.length, "end_of_response")
    }

    private fun classifyExpressionKind(source: String, start: Int, endExclusive: Int): String {
        val position = skipJavaScriptTrivia(source, start)
        val end = endExclusive.coerceIn(position, source.length)
        if (position >= end) return "empty_expression"
        return when {
            source[position] == '"' || source[position] == '\'' -> "string_literal"
            source[position] == '\u0060' -> {
                val quotedEnd = findQuotedStringEnd(source, position)
                if (quotedEnd != null && quotedEnd < end &&
                    source.substring(position + 1, quotedEnd).contains("\${")
                ) "dynamic_template_literal" else "static_template_literal"
            }
            source[position] == '(' -> "parenthesized_expression"
            source[position] == '{' -> "object_expression"
            source[position] == '[' -> "array_expression"
            readJavaScriptIdentifierEnd(source, position) != null -> {
                val identifierEnd = readJavaScriptIdentifierEnd(source, position)!!
                val next = skipJavaScriptTrivia(source, identifierEnd).coerceAtMost(end)
                when {
                    next < end && source[next] == '.' -> "member_reference"
                    next < end && source[next] == '[' -> "computed_reference"
                    next < end && source[next] == '(' -> "nested_function_call"
                    else -> "identifier_reference"
                }
            }
            source[position].isDigit() -> "numeric_expression"
            source[position] in charArrayOf('!', '+', '-', '~') -> "operator_expression"
            else -> "other_expression"
        }
    }

    private fun diagnoseStaticIdentifierResolution(
        source: String,
        identifier: String,
        referencePosition: Int,
    ): String {
        val scriptStart = source.lastIndexOf("<script", referencePosition, ignoreCase = true)
        val scriptEnd = source.lastIndexOf("</script", referencePosition, ignoreCase = true)
        if (scriptStart < 0 || scriptStart < scriptEnd) return "script_scope_not_found"
        val bodyStart = source.indexOf('>', scriptStart)
            .takeIf { it >= 0 && it < referencePosition }?.plus(1)
            ?: return "script_body_not_found"
        val scope = source.substring(bodyStart, referencePosition)
        val pattern = Regex("\\b(?:var|let|const)\\s+" + Regex.escape(identifier) + "\\s*=")
        val declarations = pattern.findAll(scope).toList().asReversed()
        for (match in declarations) {
            val declarationOffset = bodyStart + match.range.first
            if (!isJavaScriptCodePosition(source, bodyStart, declarationOffset)) continue
            if (resolveStaticStringVariable(source, identifier, referencePosition, 0) != null) {
                return "static_string_declaration_resolvable"
            }
            return "same_script_initializer_not_static_string"
        }
        if (pattern.containsMatchIn(source.substring(0, scriptStart.coerceAtLeast(0)))) {
            return "declaration_outside_current_script"
        }
        return "no_prior_static_declaration"
    }

    private fun scriptIndexAt(source: String, offset: Int): Int =
        SCRIPT_OPENING.findAll(source.substring(0, offset.coerceIn(0, source.length))).count()

    private fun responseMarkerProfile(source: String): String {
        val markers = listOf(
            "yt_initial_data" to source.contains("ytInitialData", ignoreCase = true),
            "ytcfg_set" to source.contains("ytcfg.set", ignoreCase = true),
            "ytcfg_data" to source.contains("ytcfg.data_", ignoreCase = true),
            "video_renderer" to source.contains("videoRenderer", ignoreCase = true),
            "search_results_renderer" to source.contains("twoColumnSearchResultsRenderer", ignoreCase = true),
            "consent_gate" to (
                source.contains("consent.youtube.com", ignoreCase = true) ||
                    source.contains("before you continue to youtube", ignoreCase = true)
                ),
            "bot_challenge" to (
                source.contains("unusual traffic", ignoreCase = true) ||
                    source.contains("not a robot", ignoreCase = true)
                ),
        )
        return markers.filter { it.second }.joinToString(",") { it.first }.ifEmpty { "none" }
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
    /**
     * Decode a bounded subset of JavaScript string expressions used by JSON.parse.
     * Supports quoted literals, concatenation, parentheses, decodeURIComponent and atob.
     * This parser never evaluates arbitrary JavaScript.
     */
    private fun decodeJavaScriptStringExpression(source: String, start: Int): String? {
        val decoded = parseJavaScriptStringExpression(source, start, depth = 0) ?: return null
        val closing = skipJavaScriptTrivia(source, decoded.endExclusive)
        if (closing < source.length && source[closing] == ')') return decoded.value
        if (closing < source.length && source[closing] == ',' &&
            source.substring(closing + 1).trimStart().startsWith("null)")
        ) return decoded.value
        return null
    }

    private data class DecodedStringExpression(
        val value: String,
        val endExclusive: Int,
    )

    private fun parseJavaScriptStringExpression(
        source: String,
        start: Int,
        depth: Int,
    ): DecodedStringExpression? {
        if (depth > MAX_STRING_EXPRESSION_DEPTH) return null
        val first = parseJavaScriptStringAtom(source, start, depth) ?: return null
        val value = StringBuilder(first.value)
        var end = first.endExclusive
        while (true) {
            val operator = skipJavaScriptTrivia(source, end)
            if (operator >= source.length || source[operator] != '+') break
            val next = parseJavaScriptStringAtom(source, operator + 1, depth) ?: return null
            value.append(next.value)
            end = next.endExclusive
        }
        return DecodedStringExpression(value.toString(), end)
    }

    private fun parseJavaScriptStringAtom(
        source: String,
        start: Int,
        depth: Int,
    ): DecodedStringExpression? {
        val position = skipJavaScriptTrivia(source, start)
        if (position >= source.length) return null
        val quote = source[position]
        if (quote == '"' || quote == '\'' || quote == '\u0060') {
            val end = findQuotedStringEnd(source, position) ?: return null
            val decoded = decodeJavaScriptStringLiteral(source.substring(position, end + 1)) ?: return null
            return DecodedStringExpression(decoded, end + 1)
        }
        if (source[position] == '(') {
            val nested = parseJavaScriptStringExpression(source, position + 1, depth + 1) ?: return null
            val close = skipJavaScriptTrivia(source, nested.endExclusive)
            if (close >= source.length || source[close] != ')') return null
            return DecodedStringExpression(nested.value, close + 1)
        }
        for (wrapper in listOf("decodeURIComponent", "atob")) {
            val argumentStart = callArgumentStart(source, position, wrapper) ?: continue
            val argument = parseJavaScriptStringExpression(source, argumentStart, depth + 1) ?: return null
            val close = skipJavaScriptTrivia(source, argument.endExclusive)
            if (close >= source.length || source[close] != ')') return null
            val decoded = runCatching {
                when (wrapper) {
                    "decodeURIComponent" -> java.net.URLDecoder.decode(
                        argument.value.replace("+", "%2B"),
                        StandardCharsets.UTF_8.name(),
                    )
                    "atob" -> String(
                        java.util.Base64.getDecoder().decode(argument.value),
                        StandardCharsets.UTF_8,
                    )
                    else -> null
                }
            }.getOrNull() ?: return null
            return DecodedStringExpression(decoded, close + 1)
        }
        val identifierEnd = readJavaScriptIdentifierEnd(source, position)
        if (identifierEnd != null) {
            val identifier = source.substring(position, identifierEnd)
            val resolved = resolveStaticStringVariable(source, identifier, position, depth)
            if (resolved != null) return DecodedStringExpression(resolved, identifierEnd)
        }
        return null
    }

    private fun readJavaScriptIdentifierEnd(source: String, start: Int): Int? {
        if (start >= source.length) return null
        if (!(source[start] == '_' || source[start] == '$' || source[start].isLetter())) return null
        var end = start + 1
        while (end < source.length && (source[end] == '_' || source[end] == '$' || source[end].isLetterOrDigit())) end++
        return end
    }

    private fun resolveStaticStringVariable(
        source: String, name: String, referencePosition: Int, depth: Int,
    ): String? {
        if (depth >= MAX_STRING_EXPRESSION_DEPTH || name in setOf("null", "undefined", "true", "false")) return null
        val scriptStart = source.lastIndexOf("<script", referencePosition, ignoreCase = true)
        val scriptEnd = source.lastIndexOf("</script", referencePosition, ignoreCase = true)
        if (scriptStart < 0 || scriptStart < scriptEnd) return null
        val bodyStart = source.indexOf('>', scriptStart).takeIf { it >= 0 && it < referencePosition }?.plus(1) ?: return null
        val scope = source.substring(bodyStart, referencePosition)
        val pattern = Regex("\\b(?:var|let|const)\\s+" + Regex.escape(name) + "\\s*=")
        for (match in pattern.findAll(scope).toList().asReversed()) {
            val declarationStart = bodyStart + match.range.first
            if (!isJavaScriptCodePosition(source, bodyStart, declarationStart)) continue
            val expressionStart = bodyStart + match.range.last + 1
            val parsed = parseJavaScriptStringExpression(source, expressionStart, depth + 1) ?: continue
            if (parsed.endExclusive > referencePosition) continue
            return parsed.value
        }
        return null
    }

    private fun isJavaScriptCodePosition(source: String, from: Int, target: Int): Boolean {
        var i = from
        var quote: Char? = null
        var escaped = false
        while (i < target) {
            val ch = source[i]
            if (quote != null) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == quote) quote = null
                i++; continue
            }
            if (source.startsWith("//", i)) {
                val end = source.indexOf('\n', i + 2)
                if (end < 0 || end >= target) return false
                i = end + 1; continue
            }
            if (source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2)
                if (end < 0 || end >= target) return false
                i = end + 2; continue
            }
            if (ch == '"' || ch == '\'' || ch == '\u0060') quote = ch
            i++
        }
        return quote == null
    }

    private fun decodeJavaScriptStringLiteral(literal: String): String? {
        return runCatching {
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
    }

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
            start += 4
            start = skipWhitespace(source, start)
            if (start < source.length && source[start] == ',') {
                start++
                start = skipWhitespace(source, start)
            }
        }
        if (start < source.length && source[start] == '{') {
            return extractBalancedJson(source, start)
        }

        // This is already the value of the explicit ytInitialData JSON.parse expression.
        // Allow a known prefix before JSON, but do not search the surrounding HTML or execute JS.
        for (candidate in start until source.length) {
            if (source[candidate] != '{') continue
            val json = extractBalancedJson(source, candidate) ?: continue
            if (runCatching { JsonParser.parseString(json).isJsonObject }.getOrDefault(false)) {
                return json
            }
        }
        return null
    }

    private fun createPayloadUnavailableException(
        html: String,
        extraction: InitialDataExtraction,
        fallback: InnertubeSearchFallback,
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
            expressionWrapper = extraction.expressionDiagnostics.wrapper,
            expressionKind = extraction.expressionDiagnostics.kind,
            expressionResolution = extraction.expressionDiagnostics.resolution,
            expressionFailureDetail = extraction.expressionDiagnostics.failureDetail,
            expressionLength = extraction.expressionDiagnostics.expressionLength,
            expressionStartOffset = extraction.expressionDiagnostics.expressionStartOffset,
            assignmentOffset = extraction.expressionDiagnostics.assignmentOffset,
            assignmentAttempt = extraction.expressionDiagnostics.assignmentAttempt,
            assignmentScriptIndex = extraction.expressionDiagnostics.scriptIndex,
            jsonParseArgumentCount = extraction.expressionDiagnostics.argumentCount,
            jsonParseArgumentTermination = extraction.expressionDiagnostics.argumentTermination,
            responseMarkerProfile = responseMarkerProfile(html),
            fallbackAttempted = fallback.attempted,
            fallbackOutcome = fallback.outcome,
            fallbackFailureDetail = fallback.failureDetail,
        )
    }

    private data class InitialDataExtraction(
        val payload: JsonObject?,
        val assignmentCount: Int,
        val assignmentValueKind: String,
        val failureStage: String,
        val expressionDiagnostics: PayloadExpressionDiagnostics = PayloadExpressionDiagnostics(),
    )

    private data class PayloadExpressionDiagnostics(
        val wrapper: String = "unknown",
        val kind: String = "unknown",
        val resolution: String = "not_applicable",
        val failureDetail: String = "unknown",
        val expressionLength: Int = 0,
        val expressionStartOffset: Int = -1,
        val assignmentOffset: Int = -1,
        val assignmentAttempt: Int = 0,
        val scriptIndex: Int = 0,
        val argumentCount: Int = 0,
        val argumentTermination: String = "unknown",
    )

    private fun extractBalancedJson(source: String, start: Int): String? {
        if (start >= source.length || source[start] != '{') return null

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
            """(?:window\s*\[\s*["']ytInitialData["']\s*]|window\s*\.\s*ytInitialData|\b(?:var|let|const)\s+ytInitialData\b|\bytInitialData\b)\s*=\s*""",
        )
        val SCRIPT_OPENING = Regex("""(?i)<script\b""")
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
        const val MAX_STRING_EXPRESSION_DEPTH = 16
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
    val expressionWrapper: String = "unknown",
    val expressionKind: String = "unknown",
    val expressionResolution: String = "not_applicable",
    val expressionFailureDetail: String = "unknown",
    val expressionLength: Int = 0,
    val expressionStartOffset: Int = -1,
    val assignmentOffset: Int = -1,
    val assignmentAttempt: Int = 0,
    val assignmentScriptIndex: Int = 0,
    val jsonParseArgumentCount: Int = 0,
    val jsonParseArgumentTermination: String = "unknown",
    val responseMarkerProfile: String = "unknown",
    val fallbackAttempted: Boolean = false,
    val fallbackOutcome: String = "not_attempted",
    val fallbackFailureDetail: String = "none",
) : IllegalStateException("YouTube search payload unavailable")
