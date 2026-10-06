package com.ahdownload.domain.resolver.youtube

data class YouTubeSessionSnapshot(
    val cookies: String?,
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val playerResponse: String? = null,
    val authenticated: Boolean,
    val userAgent: String? = null,
)

interface YouTubeSessionProvider {
    suspend fun snapshot(url: String): YouTubeSessionSnapshot
}
