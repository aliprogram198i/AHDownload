package com.ahdownload.domain.validation

import com.ahdownload.domain.resolver.MediaSourceContext

data class MediaProbeResult(
    val statusCode: Int,
    val contentType: String?,
    val contentLengthBytes: Long?,
    val finalUrl: String,
    val method: String = "GET",
    val range: String? = null,
)

interface MediaProbe {
    suspend fun probe(
        url: String,
        headers: Map<String, String> = emptyMap(),
        operationId: String? = null,
        sourceContext: MediaSourceContext = MediaSourceContext.RESOLVER_GENERATED,
    ): MediaProbeResult
}
