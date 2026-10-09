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
    /** Best-effort audio-track detection for browser-observed media elements. */
    val mediaHasAudioByUrl: Map<String, Boolean> = emptyMap(),
    val requestHeadersByUrl: Map<String, Map<String, String>> = emptyMap(),
    /** Categorical result of the Instagram in-session API fallback; contains no secrets. */
    val instagramApiStatus: String? = null,
    /** Number of bounded WebView media-inspection attempts made for this session. */
    val inspectionAttemptCount: Int = 0,
    /** Number of JavaScript inspection callbacks returned by WebView. */
    val inspectionCallbackCount: Int = 0,
)

interface BrowserMediaSessionProvider {
    suspend fun snapshot(url: String, platform: MediaPlatform): BrowserMediaSession
}
