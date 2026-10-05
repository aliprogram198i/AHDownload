package com.ahdownload.domain.validation

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate

class CandidateValidator(
    private val probe: MediaProbe,
) {

    suspend fun validate(candidate: MediaCandidate): CandidateValidationResult {
        val url = candidate.sourceUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return CandidateValidationResult.Invalid(
                ValidationFailure.InvalidUrl,
            )
        }

        val probeResult = runCatching { probe.probe(url) }.getOrElse {
            return CandidateValidationResult.Invalid(
                ValidationFailure.ProbeFailed,
            )
        }

        if (probeResult.statusCode !in 200..299) {
            return CandidateValidationResult.Invalid(
                ValidationFailure.HttpStatus(probeResult.statusCode),
            )
        }

        if (probeResult.contentType.isHtml()) {
            return CandidateValidationResult.Invalid(
                ValidationFailure.HtmlResponse,
            )
        }

        if (!probeResult.contentType.matchesKind(candidate.format.kind)) {
            return CandidateValidationResult.Invalid(
                ValidationFailure.ContentTypeMismatch,
            )
        }

        val updated = if (
            candidate.format.fileSizeBytes == null &&
            probeResult.contentLengthBytes != null
        ) {
            candidate.copy(
                format = candidate.format.copy(
                    fileSizeBytes = probeResult.contentLengthBytes,
                ),
            )
        } else {
            candidate
        }

        return CandidateValidationResult.Valid(
            candidate = updated,
            finalUrl = probeResult.finalUrl,
        )
    }

    private fun String?.isHtml(): Boolean =
        this?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true

    private fun String?.matchesKind(kind: MediaKind): Boolean {
        val type = this?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return when (kind) {
            MediaKind.Video -> type.startsWith("video/")
            MediaKind.Audio -> type.startsWith("audio/")
            MediaKind.Image -> type.startsWith("image/")
            MediaKind.Unknown -> false
        }
    }
}

sealed interface CandidateValidationResult {
    data class Valid(
        val candidate: MediaCandidate,
        val finalUrl: String,
    ) : CandidateValidationResult

    data class Invalid(
        val failure: ValidationFailure,
    ) : CandidateValidationResult
}

sealed interface ValidationFailure {
    data object InvalidUrl : ValidationFailure
    data object ProbeFailed : ValidationFailure
    data class HttpStatus(val code: Int) : ValidationFailure
    data object HtmlResponse : ValidationFailure
    data object ContentTypeMismatch : ValidationFailure
}
