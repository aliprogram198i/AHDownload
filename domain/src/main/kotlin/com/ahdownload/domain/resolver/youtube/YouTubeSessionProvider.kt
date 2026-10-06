package com.ahdownload.domain.resolver.youtube

data class YouTubeSessionSnapshot(
    val cookies: String?,
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val playerResponse: String?,
    val authenticated: Boolean,
)

interface YouTubeSessionProvider {
    suspend fun snapshot(url: String): YouTubeSessionSnapshot
}
