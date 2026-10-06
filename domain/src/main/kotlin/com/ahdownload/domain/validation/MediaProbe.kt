package com.ahdownload.domain.validation

data class MediaProbeResult(
    val statusCode: Int,
    val contentType: String?,
    val contentLengthBytes: Long?,
    val finalUrl: String,
    val method: String = "GET",
    val range: String? = null,
)

interface MediaProbe {
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): MediaProbeResult
}
