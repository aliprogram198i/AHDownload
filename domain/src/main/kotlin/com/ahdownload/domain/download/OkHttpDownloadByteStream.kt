package com.ahdownload.domain.download

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import kotlin.time.TimeSource

class OkHttpDownloadByteStream(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val dynamicHeaders: (String, Map<String, String>) -> Map<String, String> = { _, _ -> emptyMap() },
) : DownloadByteStream {

    override suspend fun open(
        url: String,
        rangeStart: Long,
        headers: Map<String, String>,
    ): DownloadResponse = withContext(Dispatchers.IO) {
        // A Range header observed in WebView describes that browser request, not
        // this download job. Drop it before constructing the transfer request;
        // only this engine may set Range from its actual resume offset.
        val mergedHeaders = (dynamicHeaders(url, headers) + headers)
            .filterKeys { !it.equals("Range", ignoreCase = true) }
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgentFor(url))
            .header("Accept", "*/*")

        mergedHeaders.forEach { (name, value) ->
            if (!name.equals("Host", ignoreCase = true)) builder.header(name, value)
        }

        val browserContext = isYouTubeMediaHost(url) && (
            isBrowserAlignedContext(mergedHeaders) ||
                hasPoQueryParameter(url)
            )

        if (rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        } else if (isYouTubeMediaHost(url) && !browserContext) {
            // Non-browser GVS candidates still use the validator-aligned transfer mode.
            builder.header("Range", "bytes=0-")
        }

        val started = TimeSource.Monotonic.markNow()
        var response = client.newCall(builder.build()).execute()

        if (response.code == 403 && isYouTubeMediaHost(url)) {
            response.close()

            if (browserContext) {
                // Keep the exact browser/session headers and URL intact. For a fresh
                // transfer, retry without an invented Range before changing context.
                val retryBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgentFor(url))
                    .header("Accept", "*/*")
                    .apply {
                        mergedHeaders.forEach { (name, value) ->
                            if (!name.equals("Host", ignoreCase = true)) header(name, value)
                        }
                    }
                if (rangeStart > 0L) {
                    retryBuilder.header("Range", "bytes=$rangeStart-")
                }
                val retryStarted = TimeSource.Monotonic.markNow()
                response = client.newCall(retryBuilder.build()).execute()
                logger.log(
                    if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                    "DOWNLOAD_HTTP_RETRY",
                    "إعادة محاولة مصدر YouTube بسياق المتصفح بعد 403",
                    "download.stream",
                    mapOf(
                        "host" to hostOf(url),
                        "status_code" to response.code.toString(),
                        "range_start" to rangeStart.toString(),
                        "range_header" to (response.request.header("Range") ?: "none"),
                        "elapsed_ms" to retryStarted.elapsedNow().inWholeMilliseconds.toString(),
                        "youtube_media_host" to "true",
                        "cookie_present" to mergedHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString(),
                        "referer_present" to mergedHeaders.keys.any { it.equals("Referer", ignoreCase = true) }.toString(),
                        "retry_mode" to "browser_context_preserved",
                    ),
                    null,
                )
            } else {
                val retryHeaders = mergedHeaders.filterKeys {
                    !it.equals("Cookie", ignoreCase = true) &&
                        !it.equals("Origin", ignoreCase = true) &&
                        !it.equals("Referer", ignoreCase = true)
                }
                val retryBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgentFor(url))
                    .header("Accept", "*/*")
                    .apply {
                        retryHeaders.forEach { (name, value) ->
                            if (!name.equals("Host", ignoreCase = true)) header(name, value)
                        }
                    }
                if (rangeStart > 0L) {
                    retryBuilder.header("Range", "bytes=$rangeStart-")
                } else {
                    retryBuilder.header("Range", "bytes=0-")
                }
                val retryStarted = TimeSource.Monotonic.markNow()
                response = client.newCall(retryBuilder.build()).execute()
                logger.log(
                    if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                    "DOWNLOAD_HTTP_RETRY",
                    "إعادة محاولة مصدر YouTube بعد 403",
                    "download.stream",
                    mapOf(
                        "host" to hostOf(url),
                        "status_code" to response.code.toString(),
                        "range_start" to rangeStart.toString(),
                        "range_header" to (response.request.header("Range") ?: "none"),
                        "elapsed_ms" to retryStarted.elapsedNow().inWholeMilliseconds.toString(),
                        "youtube_media_host" to "true",
                        "cookie_present" to retryHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString(),
                        "referer_present" to retryHeaders.keys.any { it.equals("Referer", ignoreCase = true) }.toString(),
                        "retry_mode" to "sanitized_headers",
                    ),
                    null,
                )
            }
        }

        if (response.code == 403 && isYouTubeMediaHost(url) && !browserContext && rangeStart == 0L) {
            response.close()
            val retryHeaders = mergedHeaders.filterKeys {
                !it.equals("Cookie", ignoreCase = true) &&
                    !it.equals("Origin", ignoreCase = true) &&
                    !it.equals("Referer", ignoreCase = true)
            }
            val retryBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgentFor(url))
                .header("Accept", "*/*")
                .apply {
                    retryHeaders.forEach { (name, value) ->
                        if (!name.equals("Host", ignoreCase = true)) header(name, value)
                    }
                }
            val retryStarted = TimeSource.Monotonic.markNow()
            response = client.newCall(retryBuilder.build()).execute()
            logger.log(
                if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                "DOWNLOAD_HTTP_RETRY",
                "إعادة محاولة مصدر YouTube بدون Range بعد 403",
                "download.stream",
                mapOf(
                    "host" to hostOf(url),
                    "status_code" to response.code.toString(),
                    "range_start" to rangeStart.toString(),
                    "range_header" to (response.request.header("Range") ?: "none"),
                    "elapsed_ms" to retryStarted.elapsedNow().inWholeMilliseconds.toString(),
                    "youtube_media_host" to "true",
                    "cookie_present" to retryHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString(),
                    "referer_present" to retryHeaders.keys.any { it.equals("Referer", ignoreCase = true) }.toString(),
                    "retry_mode" to "sanitized_no_range",
                ),
                null,
            )
        }

        if (response.code == 403 && isYouTubeMediaHost(url) && browserContext) {
            response.close()
            val stableHeaders = mergedHeaders.filterKeys {
                !it.equals("Sec-Fetch-Dest", ignoreCase = true) &&
                    !it.equals("Sec-Fetch-Mode", ignoreCase = true) &&
                    !it.equals("Sec-Fetch-Site", ignoreCase = true) &&
                    !it.equals("Sec-CH-UA", ignoreCase = true) &&
                    !it.equals("Sec-CH-UA-Mobile", ignoreCase = true) &&
                    !it.equals("Sec-CH-UA-Platform", ignoreCase = true)
            }
            val retryBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgentFor(url))
                .header("Accept", "*/*")
                .apply {
                    stableHeaders.forEach { (name, value) ->
                        if (!name.equals("Host", ignoreCase = true)) header(name, value)
                    }
                }
            if (rangeStart > 0L) {
                retryBuilder.header("Range", "bytes=$rangeStart-")
            }
            val retryStarted = TimeSource.Monotonic.markNow()
            response = client.newCall(retryBuilder.build()).execute()
            logger.log(
                if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                "DOWNLOAD_HTTP_RETRY",
                "إعادة محاولة مصدر YouTube بسياق جلسة ثابت بعد 403",
                "download.stream",
                mapOf(
                    "host" to hostOf(url),
                    "status_code" to response.code.toString(),
                    "range_start" to rangeStart.toString(),
                    "range_header" to (response.request.header("Range") ?: "none"),
                    "elapsed_ms" to retryStarted.elapsedNow().inWholeMilliseconds.toString(),
                    "youtube_media_host" to "true",
                    "cookie_present" to stableHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString(),
                    "referer_present" to stableHeaders.keys.any { it.equals("Referer", ignoreCase = true) }.toString(),
                    "retry_mode" to "stable_browser_session",
                ),
                null,
            )
        }
        val contentRange = response.header("Content-Range")
        val totalBytes = contentRange
            ?.substringAfter('/', "")
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

        logger.log(
            if (response.code in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "DOWNLOAD_HTTP_RESPONSE",
            "استجابة مصدر التنزيل",
            "download.stream",
            buildMap {
                put("host", hostOf(url))
                put("status_code", response.code.toString())
                put("range_start", rangeStart.toString())
                put("range_header", response.request.header("Range") ?: "none")
                put("content_type", response.header("Content-Type") ?: "unknown")
                put("content_length", response.body.contentLength().takeIf { it >= 0L }?.toString() ?: "unknown")
                put("total_bytes", totalBytes?.toString() ?: "unknown")
                put("elapsed_ms", started.elapsedNow().inWholeMilliseconds.toString())
                put("youtube_media_host", isYouTubeMediaHost(url).toString())
                put("cookie_present", mergedHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString())
                put("referer_present", mergedHeaders.keys.any { it.equals("Referer", ignoreCase = true) }.toString())
            },
            null,
        )

        DownloadResponse(
            statusCode = response.code,
            contentLengthBytes = response.body.contentLength().takeIf { it >= 0L },
            contentType = response.header("Content-Type"),
            body = response.body.byteStream(),
            totalBytes = totalBytes,
        )
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull() ?: "invalid"

    private fun isYouTubeMediaHost(url: String): Boolean {
        val host = hostOf(url)
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    private fun hasPoQueryParameter(url: String): Boolean =
        runCatching {
            URI(url).rawQuery.orEmpty()
                .split('&')
                .map { it.substringBefore('=').lowercase() }
                .any { it == "pot" || it == "potc" || it.contains("po_token") }
        }.getOrDefault(false)

    private fun isBrowserAlignedContext(headers: Map<String, String>): Boolean =
        headers.keys.any {
            it.equals("X-Goog-Visitor-Id", ignoreCase = true) ||
                it.equals("X-YouTube-Client-Name", ignoreCase = true) ||
                it.equals("X-YouTube-Client-Version", ignoreCase = true) ||
                it.equals("Sec-Fetch-Dest", ignoreCase = true) ||
                it.equals("Sec-CH-UA", ignoreCase = true)
        }

    private fun userAgentFor(url: String): String =
        if (isYouTubeMediaHost(url)) YOUTUBE_USER_AGENT else DEFAULT_USER_AGENT

    private companion object {
        const val DEFAULT_USER_AGENT = "AHDownload/1.0 (Android; download engine)"
        const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
