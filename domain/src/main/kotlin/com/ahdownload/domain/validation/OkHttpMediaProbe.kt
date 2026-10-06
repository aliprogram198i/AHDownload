package com.ahdownload.domain.validation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

class OkHttpMediaProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) : MediaProbe {

    override suspend fun probe(url: String): MediaProbeResult = withContext(Dispatchers.IO) {
        probeHead(url).let { head ->
            if (head.code in 200..299) {
                return@withContext head.toResult()
            }

            // Some CDNs, including media hosts used by YouTube, reject HEAD
            // while accepting a one-byte GET. A 403 from HEAD is therefore
            // not sufficient evidence that the media URL is unusable.
            if (head.code in RETRY_HEAD_CODES) {
                return@withContext probeRange(url).toResult()
            }

            head.toResult()
        }
    }

    private fun probeHead(url: String): okhttp3.Response {
        val request = baseRequest(url)
            .head()
            .build()
        return client.newCall(request).execute()
    }

    private fun probeRange(url: String): okhttp3.Response {
        val request = baseRequest(url)
            .header("Range", "bytes=0-0")
            .build()
        return client.newCall(request).execute()
    }

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgentFor(url))
            .header("Accept", "*/*")
            .apply {
                if (isYouTubeMediaHost(url)) {
                    header("Referer", "https://www.youtube.com/")
                }
            }

    private fun okhttp3.Response.toResult(): MediaProbeResult {
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
            )
        }
    }

    private fun userAgentFor(url: String): String =
        if (isYouTubeMediaHost(url)) YOUTUBE_USER_AGENT else DEFAULT_USER_AGENT

    private fun isYouTubeMediaHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "googlevideo.com" ||
            host.endsWith(".googlevideo.com") ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com")
    }

    private companion object {
        val RETRY_HEAD_CODES = setOf(403, 405, 501)

        const val DEFAULT_USER_AGENT =
            "AHDownload/1.0 (Android; media validation)"
        const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
