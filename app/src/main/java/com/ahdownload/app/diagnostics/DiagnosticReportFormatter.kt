package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DiagnosticReportFormatter {
    private const val DEFAULT_MAX_EVENTS = 36
    private const val DIAGNOSTIC_SCHEMA = 2

    fun format(logs: List<DiagnosticLog>, maxEvents: Int = DEFAULT_MAX_EVENTS): String {
        if (logs.isEmpty()) return "AHDownload Diagnostic Report\nstatus=NO_LOGS"

        val ordered = logs.sortedByDescending { it.timestampEpochMs }
        val latestError = ordered.firstOrNull { it.level == DiagnosticLevel.ERROR }
        val sessionId = latestError?.context?.get("diagnostic_session_id")
            ?: ordered.first().context["diagnostic_session_id"]
        val sessionEvents = ordered.filter {
            sessionId == null || it.context["diagnostic_session_id"] == sessionId
        }
        val anchor = latestError ?: sessionEvents.first()
        val operationId = anchor.context["operation_id"]
        val operationEvents = if (!operationId.isNullOrBlank()) {
            sessionEvents.filter { it.context["operation_id"] == operationId }
        } else sessionEvents
        val scopedEvents = operationEvents.ifEmpty { sessionEvents }
        val chronological = scopedEvents.sortedBy { it.timestampEpochMs }
        val durationMs = chronological.takeIf { it.size >= 2 }?.let {
            it.last().timestampEpochMs - it.first().timestampEpochMs
        }

        val visible = sessionEvents.count { it.type == "SMART_CENTER_OPTION_VISIBLE" }
        val hidden = sessionEvents.count { it.type == "SMART_CENTER_OPTION_HIDDEN" }
        val validationRejected = sessionEvents
            .filter {
                it.type == "MEDIA_VALIDATION_REJECTED" ||
                    (it.type == "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION" &&
                        it.context["validation_result"] == "invalid")
            }
            .mapNotNull { it.context["candidate_id"] }
            .distinct()
            .size
        val validationAccepted = sessionEvents
            .filter {
                it.type == "MEDIA_VALIDATION_ACCEPTED" ||
                    (it.type == "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION" &&
                        it.context["validation_result"] == "valid")
            }
            .mapNotNull { it.context["candidate_id"] }
            .distinct()
            .size
        val requestEvents = sessionEvents.filter(::isRequestEvent)
        val http403 = requestEvents.count { event -> statusCode(event) == 403 }
        val http4xx = requestEvents.count { event -> statusCode(event) in 400..499 }
        val http5xx = requestEvents.count { event -> statusCode(event) in 500..599 }
        val requestCount = requestEvents.size
        val youtubeEvidence = sessionEvents.lastOrNull {
            it.context.containsKey("browser_media_observed")
        }

        val status = if (latestError == null) "OK" else "FAILED"
        val rootCause = if (latestError == null) {
            "NONE"
        } else {
            when {
                http403 > 0 -> "HTTP_403"
                !anchor.context["failure_code"].isNullOrBlank() -> anchor.context["failure_code"]!!
                anchor.type.contains("VALIDATION", ignoreCase = true) -> "MEDIA_VALIDATION_FAILED"
                else -> anchor.type
            }
        }
        val classification = classify(status, rootCause, anchor)
        val action = recommendedAction(status, classification, rootCause, sessionEvents)
        val pipeline = pipelineStates(sessionEvents)
        val failure = when {
            latestError == null -> "NONE"
            validationRejected > 0 && validationAccepted == 0 -> "NO_VALID_MEDIA_SOURCE"
            else -> rootCause
        }

        val significant = chronological.filterNot {
            it.type == "SMART_CENTER_OPTION_VISIBLE" || it.type == "SMART_CENTER_OPTION_HIDDEN"
        }.takeLast(maxEvents)

        return buildString {
            appendLine("AHDownload Diagnostic")
            appendLine("diagnostic_schema=$DIAGNOSTIC_SCHEMA")
            appendLine(
                "app=${anchor.context["app_package"] ?: "unknown"} " +
                    "version=${anchor.context["app_version_name"] ?: "unknown"} " +
                    "(${anchor.context["app_version_code"] ?: "unknown"}) " +
                    "build=${anchor.context["app_build_type"] ?: "unknown"}",
            )
            appendLine(
                "android=${anchor.context["android_release"] ?: "unknown"} " +
                    "sdk=${anchor.context["android_sdk"] ?: "unknown"} " +
                    "targetSdk=${anchor.context["app_target_sdk"] ?: "unknown"}",
            )
            appendLine(
                "device=${anchor.context["device_manufacturer"] ?: "unknown"} " +
                    "${anchor.context["device_model"] ?: "unknown"}",
            )
            appendLine("session=${sessionId ?: "unknown"} events=${sessionEvents.size}")
            operationId?.let { appendLine("operation=$it") }
            durationMs?.let { appendLine("duration_ms=$it") }

            appendLine()
            appendLine("RESULT")
            appendLine("status=$status")
            appendLine("stage=${stageOf(anchor)}")
            appendLine("classification=$classification")
            appendLine("root_cause=$rootCause")
            appendLine("failure=$failure")
            appendLine("action=$action")

            appendLine()
            appendLine("PIPELINE")
            pipeline.forEach { (name, value) -> appendLine("$name=$value") }

            appendLine()
            appendLine("NETWORK")
            appendLine("requests=$requestCount")
            appendLine("http_403=$http403")
            appendLine("http_4xx=$http4xx")
            appendLine("http_5xx=$http5xx")

            appendLine()
            appendLine("MEDIA")
            appendLine("candidates=${candidateCount(sessionEvents)}")
            appendLine("accepted=$validationAccepted")
            appendLine("rejected=$validationRejected")
            appendLine("selected=${selectedCount(sessionEvents)}")

            youtubeEvidence?.let { evidence ->
                appendLine()
                appendLine("YOUTUBE")
                appendLine("browser_media_observed=${evidence.context["browser_media_observed"] ?: "unknown"}")
                appendLine("browser_request_headers_captured=${evidence.context["browser_request_headers_captured"] ?: "unknown"}")
                appendLine("browser_po_token_observed=${evidence.context["browser_po_token_observed"] ?: "unknown"}")
            }

            if (visible > 0 || hidden > 0) {
                appendLine()
                appendLine("SMART_CENTER")
                appendLine("options_extracted=${visible + hidden}")
                appendLine("visible=$visible")
                appendLine("hidden=$hidden")
            }

            appendLine()
            appendLine("FAILURE_CHAIN")
            appendLine(buildFailureChain(sessionEvents, latestError, validationRejected, validationAccepted))

            appendLine()
            appendLine("TIMELINE")
            significant.forEach { appendLine(formatEvent(it)) }
            if (significant.isEmpty()) appendLine("- no significant events")
        }.trimEnd()
    }

    private fun classify(status: String, rootCause: String, log: DiagnosticLog): String =
        if (status == "OK") {
            if (log.type.contains("SMART_CENTER", ignoreCase = true)) "UI_FLOW" else "COMPLETED"
        } else when {
            rootCause.startsWith("HTTP_") -> "NETWORK"
            rootCause.contains("VALIDATION", ignoreCase = true) || rootCause.contains("MEDIA", ignoreCase = true) -> "MEDIA_VALIDATION"
            log.type.contains("RESOLVER", ignoreCase = true) -> "MEDIA_RESOLUTION"
            log.type.contains("SMART_CENTER", ignoreCase = true) -> "UI_FLOW"
            else -> "INTERNAL"
        }

    private fun recommendedAction(
        status: String,
        classification: String,
        rootCause: String,
        events: List<DiagnosticLog>,
    ): String {
        if (status == "OK") return "NONE"
        val isYouTube = events.any {
            it.context["platform"] == "YouTube" || it.type.startsWith("youtube.", ignoreCase = true)
        }
        val captureEvidence = events.lastOrNull { it.context.containsKey("browser_media_observed") }
        return when {
            classification == "NETWORK" && rootCause == "HTTP_403" && isYouTube &&
                captureEvidence?.context["browser_media_observed"] == "0" ->
                "INSPECT_BROWSER_MEDIA_CAPTURE"
            classification == "NETWORK" && rootCause == "HTTP_403" && isYouTube &&
                captureEvidence?.context["browser_po_token_observed"] == "false" &&
                captureEvidence.context["browser_media_observed"]?.toIntOrNull()?.let { it > 0 } == true ->
                "INSPECT_YOUTUBE_PO_TOKEN_OR_CLIENT_POLICY"
            classification == "NETWORK" -> "INSPECT_REQUEST_CONTEXT"
            classification == "MEDIA_RESOLUTION" -> "INSPECT_RESOLVER"
            classification == "MEDIA_VALIDATION" -> "INSPECT_VALIDATION"
            classification == "UI_FLOW" -> "INSPECT_UI_FLOW"
            rootCause == "UNHANDLED_EXCEPTION" -> "INSPECT_STACKTRACE"
            else -> "INSPECT_FAILURE_CHAIN"
        }
    }

    private fun pipelineStates(events: List<DiagnosticLog>): LinkedHashMap<String, String> {
        fun has(type: String) = events.any { it.type == type }
        return linkedMapOf(
            "input" to (events.firstNotNullOfOrNull { it.context["input_type"] } ?: "RECEIVED"),
            "resolution" to when {
                has("SMART_CENTER_RESULT_READY") ||
                    has("MEDIA_RESOLUTION_COMPLETED") ||
                    has("RESOLUTION_COMPLETED") ||
                    events.any {
                        it.type == "SMART_CENTER_RESULT_PRESENTED" &&
                            it.context["layout_mode"] == "RESULT_READY"
                    } ||
                    events.any { it.type == "YOUTUBE_CANDIDATE_REFRESH_RESULT" } ||
                    events.any { it.type == "youtube.webview_source_selected" } -> "COMPLETED"
                events.any { it.type.contains("RESOLVER", ignoreCase = true) } -> "STARTED"
                else -> "NOT_STARTED"
            },
            "ordering" to if (has("SMART_CENTER_ORDERING")) "COMPLETED" else "NOT_STARTED",
            "presentation" to if (has("SMART_CENTER_RESULT_PRESENTED")) "COMPLETED" else "NOT_STARTED",
            "media_validation" to when {
                events.any { it.type == "MEDIA_VALIDATION_ACCEPTED" || it.type == "MEDIA_VALIDATION_REJECTED" } -> "COMPLETED"
                events.any { it.type.contains("MEDIA_VALIDATION", ignoreCase = true) } -> "STARTED"
                else -> "NOT_STARTED"
            },
            "download" to when {
                events.any { it.type.contains("DOWNLOAD_COMPLETED", ignoreCase = true) } -> "COMPLETED"
                events.any { it.operation.contains("download", ignoreCase = true) && it.type.contains("DOWNLOAD", ignoreCase = true) } -> "STARTED"
                else -> "NOT_STARTED"
            },
        )
    }

    private fun candidateCount(events: List<DiagnosticLog>): Int =
        events.mapNotNull { event ->
            if (event.type.contains("CANDIDATE", ignoreCase = true) &&
                !event.type.contains("VALIDATION", ignoreCase = true)
            ) event.context["candidate_id"] else null
        }.distinct().size

    private fun isRequestEvent(event: DiagnosticLog): Boolean =
        event.type == "MEDIA_PROBE_ATTEMPT" ||
            event.type == "HTTP_REQUEST" ||
            (event.type.endsWith("_REQUEST") &&
                event.type != "MEDIA_VALIDATION_PROBE_RESULT" &&
                event.type != "YOUTUBE_CANDIDATE_REFRESH_RESULT")

    private fun selectedCount(events: List<DiagnosticLog>): Int = events.count {
        it.type.contains("SELECTED", ignoreCase = true) || it.type == "MEDIA_SOURCE_SELECTED"
    }

    private fun statusCode(event: DiagnosticLog): Int? =
        (event.context["http_status"] ?: event.context["status_code"])?.toIntOrNull()
    private fun stageOf(log: DiagnosticLog): String =
        log.context["stage"] ?: when {
            log.type.contains("VALIDATION", ignoreCase = true) -> "MEDIA_VALIDATION"
            log.type.contains("RESOLVER", ignoreCase = true) -> "RESOLUTION"
            log.type.contains("SMART_CENTER", ignoreCase = true) -> "SMART_CENTER"
            log.operation.startsWith("download") -> "DOWNLOAD"
            log.operation.startsWith("home") -> "HOME"
            else -> log.operation
        }

    private fun buildFailureChain(
        events: List<DiagnosticLog>,
        latestError: DiagnosticLog?,
        rejected: Int,
        accepted: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (events.any { it.type.contains("RESOLVER", ignoreCase = true) || it.context["platform"] == "YouTube" }) parts += "resolver"
        if (events.any { it.type.contains("CANDIDATE", ignoreCase = true) }) parts += "candidate"
        if (events.any { it.type.contains("VALIDATION", ignoreCase = true) }) parts += "validation"
        if (rejected > 0) parts += "candidate_rejected"
        if (accepted == 0 && rejected > 0) parts += "no_valid_source"
        if (latestError?.type?.contains("SMART_CENTER", ignoreCase = true) == true) parts += "ui_error"
        if (latestError == null) return "NONE"
        return if (parts.isEmpty()) (latestError.context["failure_code"] ?: latestError.type)
        else parts.distinct().joinToString(" -> ")
    }

    private fun formatEvent(log: DiagnosticLog): String {
        val contextKeys = listOf(
            "resolver", "provider", "candidate_id", "candidate_format_id",
            "status_code", "http_status", "content_type", "duration_ms",
            "attempt", "attempts", "validation_result", "failure_code", "result",
        )
        val context = contextKeys.mapNotNull { key ->
            log.context[key]?.takeIf(String::isNotBlank)?.let { "$key=$it" }
        }.joinToString(" ")
        val sequence = log.context["event_sequence"] ?: "-"
        val base =
            "$sequence | ${formatTime(log.timestampEpochMs)} | ${log.level} | " +
                "${log.type} | ${log.operation} | ${log.reason}"
        return if (context.isBlank()) base else "$base | $context"
    }

    private fun formatTime(epochMs: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
        )
}
