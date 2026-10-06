package com.ahdownload.domain.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpDownloadByteStream(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) : DownloadByteStream {

    override suspend fun open(url: String, rangeStart: Long): DownloadResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")

        if (rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }

        val response = client.newCall(builder.build()).execute()
        val contentRange = response.header("Content-Range")
        val totalBytes = contentRange
            ?.substringAfter('/', "")
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

        DownloadResponse(
            statusCode = response.code,
            contentLengthBytes = response.body.contentLength().takeIf { it >= 0L },
            contentType = response.header("Content-Type"),
            body = response.body.byteStream(),
            totalBytes = totalBytes,
        )
    }

    private companion object {
        const val USER_AGENT = "AHDownload/1.0 (Android; download engine)"
    }
}
