package com.ahdownload.domain.resolver.youtube

data class YouTubeSessionSnapshot(
    val cookies: String?,
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val playerResponse: String? = null,
    val authenticated: Boolean,
    val userAgent: String? = null,
    val browserRequestHeaders: Map<String, Map<String, String>> = emptyMap(),
    val browserMediaObservedCount: Int = 0,
    val browserPoTokenObserved: Boolean = false,
    /**
     * Ephemeral PO token observed on a browser media request. Never persist this value.
     */
    val browserPoToken: String? = null,
)

interface YouTubeSessionProvider {
    suspend fun snapshot(url: String): YouTubeSessionSnapshot
}
