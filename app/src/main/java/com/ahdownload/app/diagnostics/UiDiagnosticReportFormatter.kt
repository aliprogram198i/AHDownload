package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object UiDiagnosticReportFormatter {
    private const val SCHEMA = 1
    private const val MAX_TIMELINE = 80

    fun format(events: List<UiTraceEvent>): String {
        if (events.isEmpty()) return "AHDownload UI Diagnostic\nui_schema=1\nstatus=NO_EVENTS"

        val ordered = events.sortedByDescending { it.timestampEpochMs }
        val latest = ordered.first()
        val sessionEvents = ordered.filter { it.sessionId == latest.sessionId }
        val snapshots = sessionEvents.filter { it.event == "UI_SNAPSHOT" }
        val interactions = sessionEvents.filter { it.event == "UI_INTERACTION" }
        val errors = sessionEvents.filter { it.level == DiagnosticLevel.ERROR }
        val screens = sessionEvents.map { it.screen }.distinct().joinToString(",")
        val first = sessionEvents.minByOrNull { it.timestampEpochMs }
        val durationMs = if (first == null) 0L else latest.timestampEpochMs - first.timestampEpochMs

        return buildString {
            appendLine("AHDownload UI Diagnostic")
            appendLine("ui_schema=$SCHEMA")
            appendLine("app=${latest.context["app_package"] ?: "unknown"} version=${latest.context["app_version_name"] ?: "unknown"} (${latest.context["app_version_code"] ?: "unknown"}) build=${latest.context["app_build_type"] ?: "unknown"}")
            appendLine("android=${latest.context["android_release"] ?: "unknown"} sdk=${latest.context["android_sdk"] ?: "unknown"} targetSdk=${latest.context["app_target_sdk"] ?: "unknown"}")
            appendLine("device=${latest.context["device_manufacturer"] ?: "unknown"} ${latest.context["device_model"] ?: "unknown"}")
            appendLine("viewport=${latest.context["viewport_width_px"] ?: "unknown"}x${latest.context["viewport_height_px"] ?: "unknown"} density=${latest.context["density"] ?: "unknown"} fontScale=${latest.context["font_scale"] ?: "unknown"} orientation=${latest.context["orientation"] ?: "unknown"}")
            appendLine("theme=${latest.context["theme_mode"] ?: "unknown"} layoutDirection=${latest.context["layout_direction"] ?: "unknown"} locale=${latest.context["locale"] ?: "unknown"}")
            appendLine("session=${latest.sessionId} events=${sessionEvents.size} duration_ms=$durationMs")
            appendLine("screens=$screens")
            appendLine()
            appendLine("UI_STATE")
            snapshots.take(12).forEach { event ->
                appendLine("${formatTime(event.timestampEpochMs)} | screen=${event.screen} | components=${event.context["components"] ?: "unknown"} | state=${event.context["state_summary"] ?: "unknown"}")
            }
            if (snapshots.isEmpty()) appendLine("- no snapshots")
            appendLine()
            appendLine("DESIGN")
            snapshots.filter { it.context["design_primary"] != null }.take(3).forEach { event ->
                appendLine("screen=${event.screen} primary=${event.context["design_primary"]} secondary=${event.context["design_secondary"]} surface=${event.context["design_surface"]} background=${event.context["design_background"]} outline=${event.context["design_outline"]}")
            }
            appendLine()
            appendLine("INTERACTIONS")
            appendLine("count=${interactions.size}")
            interactions.take(MAX_TIMELINE).forEach(::appendEvent)
            if (interactions.isEmpty()) appendLine("- none")
            appendLine()
            appendLine("ERRORS")
            appendLine("count=${errors.size}")
            errors.take(MAX_TIMELINE).forEach(::appendEvent)
            if (errors.isEmpty()) appendLine("- none")
            appendLine()
            appendLine("TIMELINE")
            sessionEvents.sortedBy { it.timestampEpochMs }.takeLast(MAX_TIMELINE).forEach(::appendEvent)
        }.trimEnd()
    }

    private fun StringBuilder.appendEvent(event: UiTraceEvent) {
        val context = event.context.toSortedMap().filterKeys { it !in ENVIRONMENT_KEYS }
            .map { (key, value) -> "$key=$value" }.joinToString(" ")
        val state = event.state?.let { " state=$it" }.orEmpty()
        val base = "${event.sequence} | ${formatTime(event.timestampEpochMs)} | ${event.level} | ${event.screen} | ${event.component} | ${event.event}$state"
        appendLine(if (context.isBlank()) base else "$base | $context")
    }

    private fun formatTime(epochMs: Long): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private val ENVIRONMENT_KEYS = setOf(
        "app_package", "app_version_name", "app_version_code", "app_build_type",
        "app_target_sdk", "app_first_install_ms", "app_last_update_ms",
        "android_sdk", "android_release", "device_manufacturer", "device_model",
        "device_brand", "device_product", "locale", "timezone", "is_24_hour_format",
        "process_id", "available_memory_bytes", "low_memory", "app_uptime_ms",
        "process_uptime_ms", "thread",
    )
}