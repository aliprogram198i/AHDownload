package com.ahdownload.domain.download

data class DownloadResponse(
    val statusCode: Int,
    val contentLengthBytes: Long?,
    val contentType: String?,
    val body: java.io.InputStream,
    val totalBytes: Long? = null,
    /** Exact start offset declared by a 206 Content-Range response. */
    val rangeStartBytes: Long? = null,
    /** Exact final offset declared by a 206 Content-Range response. */
    val rangeEndBytes: Long? = null,
)

interface DownloadByteStream {
    suspend fun open(
        url: String,
        rangeStart: Long = 0L,
        headers: Map<String, String> = emptyMap(),
    ): DownloadResponse
}
