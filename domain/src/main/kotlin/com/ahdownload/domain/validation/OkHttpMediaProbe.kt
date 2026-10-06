package com.ahdownload.domain.validation

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
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
    ): MediaProbeResult =
        withContext(Dispatchers.IO) {
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
            if (head.code in 200..299) return@withContext head.toResult("HEAD", null)

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
                return@withContext response.toResult("GET", range)
            }

            head.toResult("HEAD", null)
        }

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        range: String?,
    ): okhttp3.Response {
        val builder = baseRequest(url, headers)
        if (method == "HEAD") builder.head() else builder.get()
        range?.let { builder.header("Range", it) }
        return client.newCall(builder.build()).execute()
    }

    private fun baseRequest(url: String, headers: Map<String, String>): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgentFor(url))
            .header("Accept", "*/*")
            .apply {
                headers.forEach { (name, value) ->
                    if (!name.equals("Host", ignoreCase = true)) header(name, value)
                }
                if (isYouTubeMediaHost(url) && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
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
            if (isYouTubeMediaHost(url) && headers.keys.any { it.equals("Cookie", ignoreCase = true) }) add("Cookie")
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
