package com.ahdownload.domain.resolver

data class MediaCandidate(
    val id: String,
    val sourceUrl: String,
    val format: MediaFormat,
    /**
     * Transient request context required by some media CDNs.
     * Never persist this field; in particular, Cookie values must stay in memory.
     */
    val requestHeaders: Map<String, String> = emptyMap(),
)
