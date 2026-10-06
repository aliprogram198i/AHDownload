package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DiagnosticReportFormatter {
    private const val DEFAULT_MAX_EVENTS = 36

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
        val validationRejected = sessionEvents.count {
            it.type == "MEDIA_VALIDATION_REJECTED" ||
                (it.type == "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION" &&
                    it.context["validation_result"] == "invalid")
        }
        val validationAccepted = sessionEvents.count {
            it.type == "MEDIA_VALIDATION_ACCEPTED" ||
                (it.type == "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION" &&
                    it.context["validation_result"] == "valid")
        }
        val http403 = sessionEvents.count { event ->
            event.context["http_status"] == "403" ||
                event.context["status_code"] == "403" ||
                event.reason.contains("403", ignoreCase = true)
        }

        val rootCause = when {
            http403 > 0 -> "HTTP_403"
            !anchor.context["failure_code"].isNullOrBlank() -> anchor.context["failure_code"]!!
            anchor.type.contains("VALIDATION", ignoreCase = true) -> "MEDIA_VALIDATION_FAILED"
            else -> anchor.type
        }
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
            appendLine("status=${if (latestError == null) "OK" else "FAILED"}")
            appendLine("stage=${stageOf(anchor)}")
            appendLine("root_cause=$rootCause")
            appendLine("failure=$failure")
            appendLine("http_403_count=$http403")
            appendLine("validation_rejected=$validationRejected")
            appendLine("validation_accepted=$validationAccepted")

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
        return if (parts.isEmpty()) "no_failure_chain" else parts.distinct().joinToString(" -> ")
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
