package com.ahdownload.domain.download

import com.ahdownload.domain.model.MediaKind

enum class DownloadProcessingMode {
    Direct,
    ExtractAudio,
}

data class DownloadTask(
    val id: String,
    val sourceUrl: String,
    val destinationPath: String,
    /**
     * Non-sensitive request context captured with the media candidate.
     * Cookie headers must never be stored here; session cookies are resolved at execution time.
     */
    val requestHeaders: Map<String, String> = emptyMap(),
    val displayName: String? = null,
    val thumbnailUrl: String? = null,
    val contentFingerprint: String? = null,
    val sessionCookieHost: String? = null,
    /** Original page URL used to resolve this media source; required for refreshable sources such as YouTube. */
    val sourcePageUrl: String? = null,
    /** Media kind is persisted so a refreshed candidate can be selected deterministically. */
    val mediaKind: MediaKind? = null,
    /**
     * Processing applied after fetching the source.
     * Direct keeps the source bytes; ExtractAudio produces an audio-only file.
     */
    val processingMode: DownloadProcessingMode = DownloadProcessingMode.Direct,
    /** Desired user-visible audio output when processingMode=ExtractAudio. */
    val audioOutputFormat: AudioOutputFormat? = null,
)
