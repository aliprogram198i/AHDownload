package com.ahdownload.domain.resolver

enum class MediaSourceContext {
    RESOLVER_GENERATED,
    BROWSER_OBSERVED,
}

data class MediaCandidate(
    val id: String,
    val sourceUrl: String,
    val format: MediaFormat,
    /**
     * Transient request context required by some media CDNs.
     * Never persist this field; in particular, Cookie values must stay in memory.
     */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Host whose WebView cookie jar may be consulted transiently during execution. */
    val sessionCookieHost: String? = null,
    /**
     * Describes where the media URL came from. Browser-observed sources retain the
     * exact GVS URL/context and must not receive a PO token copied from another URL.
     */
    val sourceContext: MediaSourceContext = MediaSourceContext.RESOLVER_GENERATED,
    /**
     * Optional audio-only companion used when a video representation has no embedded
     * audio track. This pairing is transient and is never persisted by itself.
     */
    val companionAudioSourceUrl: String? = null,
    val companionAudioRequestHeaders: Map<String, String> = emptyMap(),
    val companionAudioSessionCookieHost: String? = null,
)
