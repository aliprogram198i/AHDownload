package com.ahdownload.domain.resolver.browser

import com.ahdownload.domain.model.MediaPlatform

data class BrowserMediaSession(
    val platform: MediaPlatform,
    val pageUrl: String,
    val finalUrl: String? = null,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val mediaUrls: List<String> = emptyList(),
    val requestHeadersByUrl: Map<String, Map<String, String>> = emptyMap(),
)

interface BrowserMediaSessionProvider {
    suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession
}
