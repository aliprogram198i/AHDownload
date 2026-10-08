package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Focused timeline for reconstructing exactly what happened on the HOME screen.
 *
 * The trace contains UI events plus sanitized pipeline diagnostics mirrored
 * from the application diagnostic logger. Raw URLs, query strings, cookies,
 * tokens and credentials are never written to the report.
 */
object HomeTraceReportFormatter {
    private const val SCHEMA = 2
    private const val MAX_TIMELINE = 300

    fun format(events: List<UiTraceEvent>): String {
        val homeEvents = events.filter { it.screen == "HOME" }
        if (homeEvents.isEmpty()) {
            return buildString {
                appendLine("AHDownload Home Screen Trace")
                appendLine("home_trace_schema=$SCHEMA")
                appendLine("status=NO_HOME_EVENTS")
            }.trimEnd()
        }

        val latest = homeEvents.maxBy { it.timestampEpochMs }
        val sessionEvents = homeEvents
            .filter { it.sessionId == latest.sessionId }
            .sortedBy { it.timestampEpochMs }

        val first = sessionEvents.firstOrNull()
        val durationMs = first?.let { latest.timestampEpochMs - it.timestampEpochMs } ?: 0L
        val snapshots = sessionEvents.filter { it.event == "UI_SNAPSHOT" }
        val interactions = sessionEvents.filter { it.event == "UI_INTERACTION" }
        val diagnostics = sessionEvents.filter { it.event == "HOME_DIAGNOSTIC" }
        val errors = sessionEvents.filter {
            it.level == DiagnosticLevel.ERROR || it.event == "UI_ERROR"
        }

        fun latestContextValue(key: String): String =
            sessionEvents.asSequence()
                .sortedByDescending { it.timestampEpochMs }
                .mapNotNull { it.context[key] }
                .firstOrNull { it.isNotBlank() }
                ?: "unknown"

        fun diagnosticValue(event: UiTraceEvent, key: String): String =
            event.context["diag_$key"] ?: "unknown"

        return buildString {
            appendLine("AHDownload Home Screen Trace")
            appendLine("home_trace_schema=$SCHEMA")
            appendLine(
                "app=${latestContextValue("app_package")} " +
                    "version=${latestContextValue("app_version_name")} " +
                    "(${latestContextValue("app_version_code")}) " +
                    "build=${latestContextValue("app_build_type")}",
            )
            appendLine(
                "android=${latestContextValue("android_release")} " +
                    "sdk=${latestContextValue("android_sdk")} " +
                    "targetSdk=${latestContextValue("app_target_sdk")}",
            )
            appendLine(
                "device=${latestContextValue("device_manufacturer")} " +
                    "${latestContextValue("device_model")}",
            )
            appendLine(
                "viewport=${latestContextValue("viewport_width_px")}x${latestContextValue("viewport_height_px")}px " +
                    "(${latestContextValue("viewport_width_dp")}x${latestContextValue("viewport_height_dp")}dp) " +
                    "density=${latestContextValue("density")} " +
                    "fontScale=${latestContextValue("font_scale")} " +
                    "orientation=${latestContextValue("orientation")}",
            )
            appendLine(
                "theme=${latestContextValue("theme_mode")} " +
                    "layoutDirection=${latestContextValue("layout_direction")} " +
                    "locale=${latestContextValue("locale")}",
            )
            appendLine(
                "session=${latest.sessionId} " +
                    "events=${sessionEvents.size} " +
                    "duration_ms=${durationMs}",
            )
            appendLine()

            appendLine("SUMMARY")
            appendLine("interactions=${interactions.size}")
            appendLine("snapshots=${snapshots.size}")
            appendLine("diagnostics=${diagnostics.size}")
            appendLine("errors=${errors.size}")
            appendLine(
                "operations=" +
                    diagnostics.mapNotNull { it.context["diag_operation_id"] }
                        .distinct()
                        .joinToString(",")
                        .ifBlank { "none" },
            )
            appendLine(
                "platforms=" +
                    diagnostics.mapNotNull {
                        diagnosticValue(it, "platform")
                            .takeIf { value -> value != "unknown" }
                    }
                        .distinct()
                        .joinToString(",")
                        .ifBlank { "none" },
            )

            appendLine()
            appendLine("CURRENT_STATE")
            snapshots.lastOrNull()?.let { snapshot ->
                appendLine(
                    "time=${formatTime(snapshot.timestampEpochMs)} " +
                        "state=${snapshot.context["state_summary"] ?: "unknown"}",
                )
                appendLine(
                    "components=${snapshot.context["components"] ?: "unknown"}",
                )
            } ?: appendLine("state=unknown")

            appendLine()
            appendLine("INTERACTIONS")
            interactions.takeLast(MAX_TIMELINE).forEach { appendEvent(it) }
            if (interactions.isEmpty()) appendLine("- none")

            appendLine()
            appendLine("DIAGNOSTICS")
            diagnostics.takeLast(MAX_TIMELINE).forEach { event ->
                val context = event.context.toSortedMap()
                    .filterKeys {
                        it.startsWith("diag_") &&
                            it !in TRACE_KEYS_TO_HIDE
                    }
                    .map { (key, value) -> "$key=$value" }
                    .joinToString(" ")

                val base =
                    "${event.sequence} | ${formatTime(event.timestampEpochMs)} | " +
                        "${event.level} | HOME | pipeline | HOME_DIAGNOSTIC | " +
                        "type=${diagnosticValue(event, "type")} " +
                        "operation=${diagnosticValue(event, "operation")} " +
                        "reason=${diagnosticValue(event, "reason")}"
                appendLine(if (context.isBlank()) base else "$base | $context")
            }
            if (diagnostics.isEmpty()) appendLine("- none")

            appendLine()
            appendLine("ERRORS")
            errors.takeLast(MAX_TIMELINE).forEach { appendEvent(it) }
            if (errors.isEmpty()) appendLine("- none")

            appendLine()
            appendLine("TIMELINE")
            sessionEvents.takeLast(MAX_TIMELINE).forEach { appendEvent(it) }
        }.trimEnd()
    }

    private fun StringBuilder.appendEvent(event: UiTraceEvent) {
        val context = event.context.toSortedMap()
            .filterKeys { it !in TRACE_KEYS_TO_HIDE }
            .map { (key, value) -> "$key=$value" }
            .joinToString(" ")
        val state = event.state?.let { " state=$it" }.orEmpty()
        val base =
            "${event.sequence} | ${formatTime(event.timestampEpochMs)} | ${event.level} | " +
                "${event.screen} | ${event.component} | ${event.event}$state"
        appendLine(if (context.isBlank()) base else "$base | $context")
    }

    private fun formatTime(epochMs: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
        )

    private val TRACE_KEYS_TO_HIDE = setOf(
        "app_package", "app_version_name", "app_version_code", "app_build_type",
        "app_target_sdk", "app_first_install_ms", "app_last_update_ms",
        "android_sdk", "android_release", "device_manufacturer", "device_model",
        "device_brand", "device_product", "locale", "timezone", "is_24_hour_format",
        "process_id", "available_memory_bytes", "low_memory", "app_uptime_ms",
        "diagnostic_session_id", "event_sequence", "process_uptime_ms",
        "thread", "thread_id",
    )
}
