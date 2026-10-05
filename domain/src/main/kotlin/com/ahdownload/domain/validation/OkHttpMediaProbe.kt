package com.ahdownload.domain.validation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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

            if (head.code != 405 && head.code != 501) {
                return@withContext head.toResult()
            }

            probeRange(url).toResult()
        }
    }

    private fun probeHead(url: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return client.newCall(request).execute()
    }

    private fun probeRange(url: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return client.newCall(request).execute()
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

    private companion object {
        const val USER_AGENT =
            "AHDownload/1.0 (Android; media validation)"
    }
}
