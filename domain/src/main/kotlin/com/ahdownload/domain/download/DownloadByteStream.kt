package com.ahdownload.domain.download

import java.io.InputStream

data class DownloadResponse(
    val statusCode: Int,
    val contentLengthBytes: Long?,
    val contentType: String?,
    val body: InputStream,
)

interface DownloadByteStream {
    suspend fun open(url: String): DownloadResponse
}
