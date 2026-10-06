package com.ahdownload.domain.download

data class DownloadTask(
    val id: String,
    val sourceUrl: String,
    val destinationPath: String,
    /**
     * Non-sensitive request context captured with the media candidate.
     * Cookie headers must never be stored here; session cookies are resolved at execution time.
     */
    val requestHeaders: Map<String, String> = emptyMap(),
)
