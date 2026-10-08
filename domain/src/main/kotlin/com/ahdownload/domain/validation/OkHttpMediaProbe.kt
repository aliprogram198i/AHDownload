package com.ahdownload.domain.validation

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.resolver.MediaSourceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import kotlin.time.TimeSource

class OkHttpMediaProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) : MediaProbe {

    override suspend fun probe(
        url: String,
        headers: Map<String, String>,
        operationId: String?,
        sourceContext: MediaSourceContext,
    ): MediaProbeResult =
        withContext(Dispatchers.IO) {
            if (isYouTubeMediaHost(url)) {
                probeYouTubeAligned(url, headers, operationId, sourceContext)
            } else {
                probeGeneric(url, headers, operationId)
            }
        }

    private fun probeYouTubeAligned(
        url: String,
        headers: Map<String, String>,
        operationId: String?,
        sourceContext: MediaSourceContext,
    ): MediaProbeResult {
        // Prefer the exact Range header captured from WebView when present. The
        // GVS URL and request context are session-bound; validation must not invent
        // a different transfer context before deciding that a browser-observed
        // source is invalid.
        val explicitRange = headers.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
        val browserContext = sourceContext == MediaSourceContext.BROWSER_OBSERVED || headers.keys.any {
            it.equals("X-Goog-Visitor-Id", ignoreCase = true) ||
                it.equals("X-YouTube-Client-Name", ignoreCase = true) ||
                it.equals("X-YouTube-Client-Version", ignoreCase = true) ||
                it.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                it.equals("Sec-CH-UA", ignoreCase = true)
        }
        val initialRange = explicitRange ?: "bytes=0-"
        val started = TimeSource.Monotonic.markNow()
        var response = execute(url, "GET", headers, initialRange)

        logger.log(
            level = if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            type = "MEDIA_PROBE_ATTEMPT",
            reason = if (browserContext) "youtube_browser_context_response" else "youtube_range_get_response",
            operation = "download.validate",
            context = probeContext(
                url,
                "GET",
                initialRange,
                response.code,
                response.header("Content-Type"),
                started.elapsedNow().inWholeMilliseconds,
                headers,
                operationId,
            ) + mapOf(
                "validation_mode" to if (browserContext) "YOUTUBE_BROWSER_ALIGNED" else "YOUTUBE_RANGE_ALIGNED",
                "range_source" to if (explicitRange != null) "CAPTURED_BROWSER" else "VALIDATOR_DEFAULT",
            ),
            throwable = null,
        )

        if (response.code == 403) {
            response.close()

            // First fallback: preserve the browser/session headers and exact URL,
            // but remove Range. This tests whether the endpoint rejects probing
            // semantics rather than rejecting the authenticated source itself.
            val noRangeStarted = TimeSource.Monotonic.markNow()
            response = execute(url, "GET", headers, null)
            logger.log(
                level = if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                type = "MEDIA_PROBE_ATTEMPT",
                reason = "youtube_403_browser_context_no_range_retry",
                operation = "download.validate",
                context = probeContext(
                    url,
                    "GET",
                    null,
                    response.code,
                    response.header("Content-Type"),
                    noRangeStarted.elapsedNow().inWholeMilliseconds,
                    headers,
                    operationId,
                ) + mapOf(
                    "validation_mode" to if (browserContext) {
                        "YOUTUBE_BROWSER_ALIGNED_NO_RANGE"
                    } else {
                        "YOUTUBE_403_NO_RANGE_RETRY"
                    },
                    "removed_session_headers" to "none",
                ),
                throwable = null,
            )
        }

        if (response.code == 403 && !browserContext) {
            response.close()
            val retryHeaders = headers.filterKeys {
                !it.equals("Cookie", ignoreCase = true) &&
                    !it.equals("Origin", ignoreCase = true) &&
                    !it.equals("Referer", ignoreCase = true)
            }
            val retryStarted = TimeSource.Monotonic.markNow()
            response = execute(url, "GET", retryHeaders, null)
            logger.log(
                level = if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                type = "MEDIA_PROBE_ATTEMPT",
                reason = "youtube_403_header_sanitized_no_range_retry",
                operation = "download.validate",
                context = probeContext(
                    url,
                    "GET",
                    null,
                    response.code,
                    response.header("Content-Type"),
                    retryStarted.elapsedNow().inWholeMilliseconds,
                    retryHeaders,
                    operationId,
                ) + mapOf(
                    "validation_mode" to "YOUTUBE_403_HEADER_SANITIZED_NO_RANGE_RETRY",
                    "removed_session_headers" to "Cookie,Origin,Referer",
                ),
                throwable = null,
            )
        }

        if (response.code == 403 && browserContext) {
            // Final browser-aligned fallback: remove only fetch/client hints that
            // can become stale while keeping the URL, Cookie, Origin and Referer.
            response.close()
            val stableHeaders = headers.filterKeys {
                !it.equals("Sec-Fetch-Dest", ignoreCase = true) &&
                    !it.equals("Sec-Fetch-Mode", ignoreCase = true) &&
                    !it.equals("Sec-Fetch-Site", ignoreCase = true) &&
                    !it.equals("Sec-CH-UA", ignoreCase = true) &&
                    !it.equals("Sec-CH-UA-Mobile", ignoreCase = true) &&
                    !it.equals("Sec-CH-UA-Platform", ignoreCase = true)
            }
            val retryStarted = TimeSource.Monotonic.markNow()
            response = execute(url, "GET", stableHeaders, null)
            logger.log(
                level = if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                type = "MEDIA_PROBE_ATTEMPT",
                reason = "youtube_403_stable_session_no_range_retry",
                operation = "download.validate",
                context = probeContext(
                    url,
                    "GET",
                    null,
                    response.code,
                    response.header("Content-Type"),
                    retryStarted.elapsedNow().inWholeMilliseconds,
                    stableHeaders,
                    operationId,
                ) + mapOf(
                    "validation_mode" to "YOUTUBE_BROWSER_STABLE_SESSION_RETRY",
                    "removed_session_headers" to "Sec-Fetch-*,Sec-CH-UA-*",
                ),
                throwable = null,
            )
        }

        return response.toResult("GET", response.request.header("Range"))
    }

    private fun probeGeneric(
        url: String,
        headers: Map<String, String>,
        operationId: String?,
    ): MediaProbeResult {
        val started = TimeSource.Monotonic.markNow()
        val head = execute(url, "HEAD", headers, null)
        logger.log(
            level = if (head.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            type = "MEDIA_PROBE_ATTEMPT",
            reason = "head_response",
            operation = "download.validate",
            context = probeContext(
                url,
                "HEAD",
                null,
                head.code,
                head.header("Content-Type"),
                started.elapsedNow().inWholeMilliseconds,
                headers,
                operationId,
            ),
            throwable = null,
        )
        if (head.code in 200..299) return head.toResult("HEAD", null)

        if (head.code in RETRY_HEAD_CODES) {
            head.close()
            val range = "bytes=0-0"
            val rangeStarted = TimeSource.Monotonic.markNow()
            val response = execute(url, "GET", headers, range)
            logger.log(
                level = if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                type = "MEDIA_PROBE_ATTEMPT",
                reason = "range_get_response",
                operation = "download.validate",
                context = probeContext(
                    url,
                    "GET",
                    range,
                    response.code,
                    response.header("Content-Type"),
                    rangeStarted.elapsedNow().inWholeMilliseconds,
                    headers,
                    operationId,
                ),
                throwable = null,
            )
            return response.toResult("GET", range)
        }

        return head.toResult("HEAD", null)
    }

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        range: String?,
    ): okhttp3.Response {
        val builder = baseRequest(url, headers, range)
        if (method == "HEAD") builder.head() else builder.get()
        range?.let { builder.header("Range", it) }
        return client.newCall(builder.build()).execute()
    }

    private fun baseRequest(
        url: String,
        headers: Map<String, String>,
        range: String?,
    ): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgentFor(url))
            .header("Accept", "*/*")
            .apply {
                headers.forEach { (name, value) ->
                    if (
                        !name.equals("Host", ignoreCase = true) &&
                        !(range == null && name.equals("Range", ignoreCase = true))
                    ) {
                        header(name, value)
                    }
                }
                if (
                    isYouTubeMediaHost(url) &&
                    headers.keys.none { it.equals("Referer", ignoreCase = true) } &&
                    headers.keys.none { it.equals("X-Goog-Visitor-Id", ignoreCase = true) } &&
                    headers.keys.none { it.equals("X-YouTube-Client-Name", ignoreCase = true) }
                ) {
                    header("Referer", "https://www.youtube.com/")
                }
            }

    private fun okhttp3.Response.toResult(method: String, range: String?): MediaProbeResult {
        use {
            val totalSize = header("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?: body.contentLength().takeIf { it >= 0L }

            return MediaProbeResult(
                statusCode = code,
                contentType = header("Content-Type"),
                contentLengthBytes = totalSize,
                finalUrl = request.url.toString(),
                method = method,
                range = range,
            )
        }
    }

    private fun probeContext(
        url: String,
        method: String,
        range: String?,
        code: Int,
        contentType: String?,
        elapsedMs: Long,
        headers: Map<String, String>,
        operationId: String?,
    ): Map<String, String> = buildMap {
        put("host", hostOf(url))
        put("method", method)
        put("status_code", code.toString())
        put("elapsed_ms", elapsedMs.toString())
        put("content_type", contentType ?: "unknown")
        put("range", range ?: "none")
        put("stage", "MEDIA_VALIDATION")
        put("youtube_media_host", isYouTubeMediaHost(url).toString())
        put("request_header_names", headers.keys.sorted().joinToString(",").ifBlank { "none" })
        put("effective_header_names", buildList {
            add("User-Agent")
            add("Accept")
            headers.keys.filterNot { it.equals("Host", ignoreCase = true) }.forEach(::add)
            if (isYouTubeMediaHost(url) && headers.keys.none { it.equals("Referer", ignoreCase = true) }) add("Referer")
        }.distinct().joinToString(","))
        put("cookie_present", headers.keys.any { it.equals("Cookie", ignoreCase = true) }.toString())
        put("referer_present", (headers.keys.any { it.equals("Referer", ignoreCase = true) } || isYouTubeMediaHost(url)).toString())
        operationId?.let { put("operation_id", it) }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull() ?: "invalid"

    private fun userAgentFor(url: String): String =
        if (isYouTubeMediaHost(url)) YOUTUBE_USER_AGENT else DEFAULT_USER_AGENT

    private fun isYouTubeMediaHost(url: String): Boolean {
        val host = hostOf(url)
        return host == "googlevideo.com" ||
            host.endsWith(".googlevideo.com") ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com")
    }

    private companion object {
        val RETRY_HEAD_CODES = setOf(403, 405, 501)
        const val DEFAULT_USER_AGENT = "AHDownload/1.0 (Android; media validation)"
        const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
