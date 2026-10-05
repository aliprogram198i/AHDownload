package com.ahdownload.domain.validation

data class MediaProbeResult(
    val statusCode: Int,
    val contentType: String?,
    val contentLengthBytes: Long?,
    val finalUrl: String,
)

interface MediaProbe {
    suspend fun probe(url: String): MediaProbeResult
}
