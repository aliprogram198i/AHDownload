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
        val mergedHeaders = dynamicHeaders(url, headers) + headers
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgentFor(url))
            .header("Accept", "*/*")

        mergedHeaders.forEach { (name, value) ->
            if (!name.equals("Host", ignoreCase = true)) builder.header(name, value)
        }

        if (rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }

        val started = TimeSource.Monotonic.markNow()
        val response = client.newCall(builder.build()).execute()
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

    private fun userAgentFor(url: String): String =
        if (isYouTubeMediaHost(url)) YOUTUBE_USER_AGENT else DEFAULT_USER_AGENT

    private companion object {
        const val DEFAULT_USER_AGENT = "AHDownload/1.0 (Android; download engine)"
        const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
