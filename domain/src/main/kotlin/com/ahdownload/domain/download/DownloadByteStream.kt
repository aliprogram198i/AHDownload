package com.ahdownload.domain.download

data class DownloadResponse(
    val statusCode: Int,
    val contentLengthBytes: Long?,
    val contentType: String?,
    val body: java.io.InputStream,
    val totalBytes: Long? = null,
)

interface DownloadByteStream {
    suspend fun open(
        url: String,
        rangeStart: Long = 0L,
        headers: Map<String, String> = emptyMap(),
    ): DownloadResponse
}
