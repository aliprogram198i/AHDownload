package com.ahdownload.app.download

import com.ahdownload.domain.resolver.MediaCandidate

/**
 * Deterministic policies used when deciding whether and how a download is retried.
 * These functions do not perform I/O or mutate task state.
 */
internal object DownloadRetryPolicy {
    fun youtubeRefreshPriority(candidate: MediaCandidate): Int = when {
        candidate.id.startsWith("embedded-") -> 0
        candidate.sourceContext == com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED -> 1
        candidate.id.startsWith("android-") -> 2
        else -> 3
    }
    
    fun interleaveYouTubeRefreshCandidates(
        primary: List<MediaCandidate>,
        fallback: List<MediaCandidate>,
    ): List<MediaCandidate> {
        val result = ArrayList<MediaCandidate>(primary.size + fallback.size)
        val limit = maxOf(primary.size, fallback.size)
        for (index in 0 until limit) {
            primary.getOrNull(index)?.let(result::add)
            fallback.getOrNull(index)?.let(result::add)
        }
        return result.distinctBy { it.id }
    }
    
    fun isRetryableHttp(detail: String?): Boolean {
        val code = detail?.toIntOrNull() ?: return false
        return code == 408 || code == 429 || code in 500..599
    }
}
