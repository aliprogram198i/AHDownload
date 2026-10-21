package com.ahdownload.domain.validation

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.download.DownloadUrlPolicy
import com.ahdownload.domain.resolver.MediaCandidate

class CandidateValidator(
    private val probe: MediaProbe,
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) {

    suspend fun validate(
        candidate: MediaCandidate,
        operationId: String? = null,
        transientRequestHeaders: Map<String, String> = emptyMap(),
    ): CandidateValidationResult {
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
        if (!DownloadUrlPolicy.isSecureHttpsUrl(url)) {
            return reject(ValidationFailure.InvalidUrl)
        }

        // Session headers are used for this probe only. Keep the candidate secret-free.
        val probeHeaders = mergeHeaders(candidate.requestHeaders, transientRequestHeaders)

        logger.log(
            DiagnosticLevel.INFO,
            "MEDIA_VALIDATION_STARTED",
            "بدء التحقق من مصدر الوسائط",
            "download.validate",
            buildMap {
                put("candidate_id", candidate.id)
                put("host", hostOf(url))
                put("requested_kind", candidate.format.kind.name)
                put("request_header_names", probeHeaders.keys.sorted().joinToString(",").ifBlank { "none" })
                put("cookie_present", probeHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }.toString())
                put("stage", "MEDIA_VALIDATION")
                operationId?.let { put("operation_id", it) }
            },
            null,
        )

        val probeResult = runCatching {
            probe.probe(
                url = url,
                headers = probeHeaders,
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

        if (!DownloadUrlPolicy.isSecureHttpsUrl(probeResult.finalUrl)) {
            return reject(ValidationFailure.InvalidUrl)
        }

        if (probeResult.statusCode !in 200..299) {
            return reject(ValidationFailure.HttpStatus(probeResult.statusCode))
        }

        if (probeResult.contentType.isHtml()) {
            return reject(ValidationFailure.HtmlResponse)
        }

        if (
            candidate.streamingManifest &&
            !probeResult.contentType.isStreamingManifest()
        ) {
            return reject(ValidationFailure.ContentTypeMismatch)
        }

        val snapchatOpaqueVideoResponse =
            candidate.format.kind == MediaKind.Video &&
                probeResult.contentType.isGenericBinary() &&
                isSnapchatMediaCdn(probeResult.finalUrl)
        val knownExtensionBinaryResponse =
            !candidate.streamingManifest &&
                probeResult.contentType.isGenericBinary() &&
                (
                    urlSupportsMediaKind(url, candidate.format.kind) ||
                        urlSupportsMediaKind(probeResult.finalUrl, candidate.format.kind)
                    )
        val oggVideoResponse =
            !candidate.streamingManifest &&
                candidate.format.kind == MediaKind.Video &&
                probeResult.contentType.isOggContainer() &&
                listOf(url, probeResult.finalUrl).any { urlExtension(it) == "ogv" }

        if (
            !candidate.streamingManifest &&
            !probeResult.contentType.matchesKind(candidate.format.kind) &&
            !snapchatOpaqueVideoResponse &&
            !knownExtensionBinaryResponse &&
            !oggVideoResponse
        ) {
            return reject(ValidationFailure.ContentTypeMismatch)
        }

        val measuredContentLength = probeResult.contentLengthBytes?.takeIf { it > 0L }
        val updatedFormat = when {
            candidate.streamingManifest -> candidate.format.copy(fileSizeBytes = null)
            measuredContentLength != null -> candidate.format.copy(fileSizeBytes = measuredContentLength)
            else -> candidate.format
        }
        val updated = candidate.copy(
            format = updatedFormat,
            validatedContentLengthBytes = measuredContentLength,
        )

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

    private fun mergeHeaders(
        original: Map<String, String>,
        transient: Map<String, String>,
    ): Map<String, String> = buildMap {
        putAll(original)
        transient.forEach { (name, value) ->
            val existingName = keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existingName != null) put(existingName, value) else put(name, value)
        }
    }

    private fun failureCode(failure: ValidationFailure): String = when (failure) {
        ValidationFailure.InvalidUrl -> "INVALID_URL"
        ValidationFailure.ProbeFailed -> "PROBE_FAILED"
        is ValidationFailure.HttpStatus -> "HTTP_${failure.code}"
        ValidationFailure.HtmlResponse -> "HTML_RESPONSE"
        ValidationFailure.ContentTypeMismatch -> "CONTENT_TYPE_MISMATCH"
    }

    private fun isSnapchatMediaCdn(url: String): Boolean {
        val host = hostOf(url)
        return host == "sc-cdn.net" || host.endsWith(".sc-cdn.net")
    }

    private fun String?.isGenericBinary(): Boolean =
        this?.substringBefore(';')?.trim()?.lowercase() in setOf(
            "application/octet-stream",
            "binary/octet-stream",
        )

    private fun String?.isOggContainer(): Boolean =
        this?.substringBefore(';')?.trim()?.lowercase() in setOf(
            "application/ogg",
            "video/ogg",
        )

    private fun urlExtension(url: String): String =
        runCatching { java.net.URI(url).path.orEmpty().substringAfterLast('.', "").lowercase() }
            .getOrDefault("")

    private fun urlSupportsMediaKind(url: String, kind: MediaKind): Boolean {
        val extension = urlExtension(url)
        return when (kind) {
            MediaKind.Video -> extension in VIDEO_EXTENSIONS
            MediaKind.Audio -> extension in AUDIO_EXTENSIONS
            MediaKind.Image -> extension in IMAGE_EXTENSIONS
            MediaKind.Unknown -> false
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: "invalid"

    private fun String?.isHtml(): Boolean =
        this?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true

    private fun String?.isStreamingManifest(): Boolean {
        val type = this?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "application/vnd.apple.mpegurl" ||
            type == "application/x-mpegurl" ||
            type == "application/mpegurl" ||
            type == "audio/mpegurl" ||
            type == "application/dash+xml" ||
            type == "text/vnd.apple.mpegurl" ||
            type == "text/plain"
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "avi", "ogv")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }

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
