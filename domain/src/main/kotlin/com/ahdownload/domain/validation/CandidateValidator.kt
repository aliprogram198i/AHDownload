package com.ahdownload.domain.validation

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate

class CandidateValidator(
    private val probe: MediaProbe,
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) {

    suspend fun validate(candidate: MediaCandidate, operationId: String? = null): CandidateValidationResult {
        fun reject(failure: ValidationFailure): CandidateValidationResult.Invalid {
            logger.log(
                DiagnosticLevel.WARNING,
                "MEDIA_VALIDATION_REJECTED",
                failureCode(failure),
                "download.validate",
                buildMap {
                    put("candidate_id", candidate.id)
                    put("failure_code", failureCode(failure))
                    put("stage", "MEDIA_VALIDATION")
                    operationId?.let { put("operation_id", it) }
                    if (failure is ValidationFailure.HttpStatus) {
                        put("http_status", failure.code.toString())
                    }
                },
                null,
            )
            return CandidateValidationResult.Invalid(failure)
        }

        val url = candidate.sourceUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return reject(ValidationFailure.InvalidUrl)
        }

        logger.log(
            DiagnosticLevel.INFO,
            "MEDIA_VALIDATION_STARTED",
            "بدء التحقق من مصدر الوسائط",
            "download.validate",
            buildMap {
                put("candidate_id", candidate.id)
                put("host", hostOf(url))
                put("requested_kind", candidate.format.kind.name)
                put("request_header_names", candidate.requestHeaders.keys.sorted().joinToString(",").ifBlank { "none" })
                put("cookie_present", candidate.requestHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString())
                put("stage", "MEDIA_VALIDATION")
                operationId?.let { put("operation_id", it) }
            },
            null,
        )

        val probeResult = runCatching {
            probe.probe(
                url = url,
                headers = candidate.requestHeaders,
                operationId = operationId,
                sourceContext = candidate.sourceContext,
            )
        }.getOrElse {
            return reject(ValidationFailure.ProbeFailed)
        }

        logger.log(
            if (probeResult.statusCode in 200..299) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "MEDIA_VALIDATION_PROBE_RESULT",
            "اكتملت محاولة التحقق من المصدر",
            "download.validate",
            buildMap {
                put("candidate_id", candidate.id)
                put("host", hostOf(probeResult.finalUrl))
                put("status_code", probeResult.statusCode.toString())
                put("method", probeResult.method)
                put("range", probeResult.range ?: "none")
                put("content_type", probeResult.contentType ?: "unknown")
                put("content_length_bytes", probeResult.contentLengthBytes?.toString() ?: "unknown")
                put("stage", "MEDIA_VALIDATION")
                operationId?.let { put("operation_id", it) }
            },
            null,
        )

        if (probeResult.statusCode !in 200..299) {
            return reject(ValidationFailure.HttpStatus(probeResult.statusCode))
        }

        if (probeResult.contentType.isHtml()) {
            return reject(ValidationFailure.HtmlResponse)
        }

        if (!probeResult.contentType.matchesKind(candidate.format.kind)) {
            return reject(ValidationFailure.ContentTypeMismatch)
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

        logger.log(
            DiagnosticLevel.INFO,
            "MEDIA_VALIDATION_ACCEPTED",
            "تم قبول مصدر الوسائط بعد التحقق",
            "download.validate",
            buildMap {
                put("candidate_id", candidate.id)
                put("stage", "MEDIA_VALIDATION")
                put("validation_result", "valid")
                put("status_code", probeResult.statusCode.toString())
                put("content_type", probeResult.contentType ?: "unknown")
                operationId?.let { put("operation_id", it) }
            },
            null,
        )

        return CandidateValidationResult.Valid(
            candidate = updated,
            finalUrl = probeResult.finalUrl,
        )
    }

    private fun failureCode(failure: ValidationFailure): String = when (failure) {
        ValidationFailure.InvalidUrl -> "INVALID_URL"
        ValidationFailure.ProbeFailed -> "PROBE_FAILED"
        is ValidationFailure.HttpStatus -> "HTTP_${failure.code}"
        ValidationFailure.HtmlResponse -> "HTML_RESPONSE"
        ValidationFailure.ContentTypeMismatch -> "CONTENT_TYPE_MISMATCH"
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: "invalid"

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
