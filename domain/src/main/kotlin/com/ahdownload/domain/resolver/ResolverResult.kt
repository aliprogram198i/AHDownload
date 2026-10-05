package com.ahdownload.domain.resolver

sealed interface ResolverResult {
    data class Success(
        val title: String?,
        val thumbnailUrl: String?,
        val durationMs: Long?,
        val candidates: List<MediaCandidate>,
    ) : ResolverResult

    data class Failure(
        val code: FailureCode,
        val message: String? = null,
    ) : ResolverResult
}

enum class FailureCode {
    UnsupportedPlatform,
    UnsupportedMediaKind,
    NoCandidates,
    InvalidRequest,
    ResolverUnavailable,
}
