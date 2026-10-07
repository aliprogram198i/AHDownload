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

    /**
     * Uses YouTube's embedded-player Innertube client as a deterministic fallback.
     * The embedded client currently does not require a GVS PO token, but only exposes
     * videos that are available for embedding. This is intentionally attempted only
     * after the normal player path fails.
     */
    suspend fun fetchEmbeddedPlayerResponse(
        html: String,
        videoUrl: String,
        headers: Map<String, String> = emptyMap(),
        operationId: String? = null,
    ): String? {
        val videoId = extractVideoId(videoUrl) ?: return null
        val contextJson = extractObject(html, "INNERTUBE_CONTEXT") ?: return null
        val context = runCatching { JsonParser.parseString(contextJson).asJsonObject.deepCopy() }.getOrElse { error ->
            logFailure("youtube.embedded_context_parse_failed", "تعذر تجهيز سياق YouTube المضمّن: " + (error.message ?: error::class.simpleName.orEmpty()), videoId, operationId, error)
            return null
        }
        val client = context.getAsJsonObject("client") ?: JsonObject().also { context.add("client", it) }
        client.addProperty("clientName", "WEB_EMBEDDED_PLAYER")
        client.addProperty("clientVersion", EMBEDDED_CLIENT_VERSION)
        client.addProperty("originalUrl", "https://www.youtube.com/embed/$videoId?html5=1")

        val thirdParty = context.getAsJsonObject("thirdParty") ?: JsonObject().also { context.add("thirdParty", it) }
        thirdParty.addProperty("embedUrl", "https://www.youtube.com/")

        val payload = JsonObject().apply {
            add("context", context)
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }
        val requestHeaders = buildMap {
            putAll(headers)
            put("X-YouTube-Client-Name", EMBEDDED_CLIENT_NAME)
            put("X-YouTube-Client-Version", EMBEDDED_CLIENT_VERSION)
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            client.get("visitorData")?.asString?.takeIf { it.isNotBlank() }?.let { put("X-Goog-Visitor-Id", it) }
            client.get("userAgent")?.asString?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val apiKey = extractQuotedValue(html, "INNERTUBE_API_KEY") ?: return null
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=" +
            URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())

        return runCatching { httpClient.postJson(endpoint, payload.toString(), requestHeaders) }.getOrElse { error ->
            logFailure("youtube.embedded_player_failed", "فشل مسار YouTube Embedded Player: " + (error.message ?: error::class.simpleName.orEmpty()), videoId, operationId, error)
            null
        }
    }

    /**
     * Uses the first-party Android Innertube client as a download-oriented fallback.
     * This is intentionally after WEB/embedded because YouTube can selectively force
     * SABR on web clients, while the Android client can still expose direct GVS URLs.
     */
    suspend fun fetchAndroidPlayerResponse(
        html: String,
        videoUrl: String,
        headers: Map<String, String> = emptyMap(),
        operationId: String? = null,
    ): String? {
        val videoId = extractVideoId(videoUrl) ?: return null
        val contextJson = extractObject(html, "INNERTUBE_CONTEXT") ?: return null
        val context = runCatching { JsonParser.parseString(contextJson).asJsonObject.deepCopy() }.getOrElse { error ->
            logFailure("youtube.android_context_parse_failed", "تعذر تجهيز سياق YouTube Android: " + (error.message ?: error::class.simpleName.orEmpty()), videoId, operationId, error)
            return null
        }
        val client = context.getAsJsonObject("client") ?: JsonObject().also { context.add("client", it) }
        client.addProperty("clientName", ANDROID_CLIENT_NAME)
        client.addProperty("clientVersion", ANDROID_CLIENT_VERSION)
        client.addProperty("androidSdkVersion", ANDROID_SDK_VERSION)
        client.addProperty("userAgent", ANDROID_USER_AGENT)
        client.addProperty("osName", "Android")
        client.addProperty("osVersion", "11")
        context.remove("thirdParty")

        val payload = JsonObject().apply {
            add("context", context)
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }
        val apiKey = extractQuotedValue(html, "INNERTUBE_API_KEY") ?: return null
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=" +
            URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        val requestHeaders = buildMap {
            putAll(headers)
            put("X-YouTube-Client-Name", ANDROID_CLIENT_NAME)
            put("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION)
            put("User-Agent", ANDROID_USER_AGENT)
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
        }

        return runCatching { httpClient.postJson(endpoint, payload.toString(), requestHeaders) }.getOrElse { error ->
            logFailure("youtube.android_player_failed", "فشل مسار YouTube Android Player: " + (error.message ?: error::class.simpleName.orEmpty()), videoId, operationId, error)
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

    private companion object {
        const val EMBEDDED_CLIENT_NAME = "56"
        const val EMBEDDED_CLIENT_VERSION = "2.20260708.00.00"
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
