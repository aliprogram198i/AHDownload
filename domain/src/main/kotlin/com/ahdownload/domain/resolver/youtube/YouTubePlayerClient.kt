package com.ahdownload.domain.resolver.youtube

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.resolver.HttpTextClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class YouTubePlayerClient(
    private val httpClient: HttpTextClient,
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) {
    suspend fun fetchPlayerResponse(html: String, videoUrl: String, headers: Map<String, String> = emptyMap(), operationId: String? = null): String? {
        val videoId = extractVideoId(videoUrl)
        val apiKey = extractQuotedValue(html, "INNERTUBE_API_KEY") ?: run {
            logFailure("youtube.player_api_key_missing", "لم يتم العثور على INNERTUBE_API_KEY.", videoId, operationId)
            return null
        }

        val contextJson = extractObject(html, "INNERTUBE_CONTEXT") ?: run {
            logFailure("youtube.player_context_missing", "لم يتم العثور على INNERTUBE_CONTEXT.", videoId, operationId)
            return null
        }
        val context = runCatching { JsonParser.parseString(contextJson).asJsonObject }.getOrElse { error ->
            logFailure("youtube.player_context_parse_failed", "تعذر تحليل INNERTUBE_CONTEXT: " + (error.message ?: error::class.simpleName.orEmpty()), videoId, operationId, error)
            return null
        }
        val payload = JsonObject().apply {
            add("context", context)
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }

        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=" +
            URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())

        return runCatching { httpClient.postJson(endpoint, payload.toString(), headers) }.getOrElse { error ->
            logFailure("youtube.player_request_failed", "فشل طلب YouTube Player API: " + (error.message ?: error::class.simpleName.orEmpty()), videoId, operationId, error)
            null
        }
    }

    private fun logFailure(type: String, reason: String, videoId: String?, operationId: String?, error: Throwable? = null) {
        logger.log(
            DiagnosticLevel.WARNING,
            type,
            reason,
            "youtube.resolve",
            buildMap {
                videoId?.let { put("video_id", it) }
                operationId?.takeIf { it.isNotBlank() }?.let { put("operation_id", it) }
            },
            error,
        )
    }

    private fun extractQuotedValue(html: String, name: String): String? {
        val markers = listOf(
            "\"$name\":\"",
            "$name = \"",
            "$name=\"",
        )
        for (marker in markers) {
            val start = html.indexOf(marker)
            if (start < 0) continue
            val valueStart = start + marker.length
            val end = html.indexOf('"', valueStart)
            if (end > valueStart) return html.substring(valueStart, end)
        }
        return null
    }

    private fun extractObject(html: String, name: String): String? {
        val marker = "\"$name\":"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val objectStart = html.indexOf('{', start + marker.length)
        if (objectStart < 0) return null
        val end = findJsonObjectEnd(html, objectStart)
        return if (end > objectStart) html.substring(objectStart, end + 1) else null
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{6,})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{6,})"),
            Regex("/shorts/([A-Za-z0-9_-]{6,})"),
            Regex("/embed/([A-Za-z0-9_-]{6,})"),
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    private fun findJsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }
}
