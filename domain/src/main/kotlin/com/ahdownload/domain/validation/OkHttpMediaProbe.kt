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
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            MediaProbeResult(
                statusCode = response.code,
                contentType = response.header("Content-Type"),
                contentLengthBytes = response.body.contentLength().takeIf { it >= 0L },
                finalUrl = response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val USER_AGENT =
            "AHDownload/1.0 (Android; media validation)"
    }
}
