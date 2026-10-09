package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dedicated timeline for Smart Center result-card diagnostics. */
object ResultCardTraceReportFormatter {
    private const val SCHEMA = 1
    private const val MAX_EVENTS = 300

    fun format(events: List<UiTraceEvent>): String {
        val cardEvents = events.filter { it.screen == "RESULT_CARD" }
        if (cardEvents.isEmpty()) {
            return "AHDownload Result Card Trace\nresult_card_trace_schema=" + SCHEMA +
                "\nstatus=NO_RESULT_CARD_EVENTS"
        }
        val latest = cardEvents.maxBy { it.timestampEpochMs }
        val session = cardEvents.filter { it.sessionId == latest.sessionId }.sortedBy { it.timestampEpochMs }
        val generation = latest.context["result_generation"].orEmpty()
        val current = session.filter { generation.isBlank() || it.context["result_generation"] == generation }
        val snapshot = current.lastOrNull { it.event == "RESULT_SNAPSHOT" }
        val sources = current.filter { it.event == "SOURCE_CANDIDATE" }
        val options = current.filter { it.event == "RESULT_OPTION" }
        val actions = current.filter { it.event == "USER_ACTION" || it.event == "UI_INTERACTION" }
        val states = current.filter { it.event == "CARD_STATE" }
        val errors = session.filter {
            it.level == DiagnosticLevel.ERROR || it.event == "RESULT_CARD_ERROR" ||
                (it.event == "CARD_STATE" && it.state == "ERROR")
        }

        fun env(key: String): String = session.asSequence()
            .sortedByDescending { it.timestampEpochMs }
            .mapNotNull { it.context[key] }
            .firstOrNull { it.isNotBlank() } ?: "unknown"

        return buildString {
            appendLine("AHDownload Result Card Trace")
            appendLine("result_card_trace_schema=" + SCHEMA)
            appendLine("app=" + env("app_package") + " version=" + env("app_version_name") +
                " (" + env("app_version_code") + ") build=" + env("app_build_type"))
            appendLine("android=" + env("android_release") + " sdk=" + env("android_sdk") +
                " targetSdk=" + env("app_target_sdk"))
            appendLine("device=" + env("device_manufacturer") + " " + env("device_model"))
            appendLine("trace_session=" + latest.sessionId.take(8) +
                " card_events=" + session.size + " generations=" +
                session.mapNotNull { it.context["result_generation"] }.distinct().size)
            appendLine("current_result_generation=" + generation.ifBlank { "unknown" })
            appendLine()
            appendLine("SUMMARY")
            appendLine("current_events=" + current.size)
            appendLine("raw_source_candidates=" + sources.size)
            appendLine("result_options=" + options.size)
            appendLine("user_actions=" + actions.size)
            appendLine("state_transitions=" + states.size)
            appendLine("session_errors=" + errors.size)
            appendLine("latest_state=" + (states.lastOrNull()?.state ?: "unknown"))
            appendLine()
            appendLine("LATEST_RESULT_SNAPSHOT")
            appendLine(snapshot?.let(::formatEvent) ?: "- none")
            appendLine()
            appendLine("SOURCE_CANDIDATES")
            if (sources.isEmpty()) appendLine("- none") else sources.forEach { appendLine(formatEvent(it)) }
            appendLine()
            appendLine("RESULT_OPTIONS")
            if (options.isEmpty()) appendLine("- none") else options.forEach { appendLine(formatEvent(it)) }
            appendLine()
            appendLine("SELECTION_AND_DOWNLOAD_ACTIVITY")
            val activity = current.filter { it.event == "USER_ACTION" || it.event == "UI_INTERACTION" || it.event == "CARD_STATE" }
            if (activity.isEmpty()) appendLine("- none") else activity.forEach { appendLine(formatEvent(it)) }
            appendLine()
            appendLine("ERRORS")
            if (errors.isEmpty()) appendLine("- none") else errors.takeLast(MAX_EVENTS).forEach { appendLine(formatEvent(it)) }
            appendLine()
            appendLine("SESSION_TIMELINE")
            session.takeLast(MAX_EVENTS)
                .filterNot {
                    it.context["result_generation"] != generation &&
                        it.event in setOf("RESULT_SNAPSHOT", "SOURCE_CANDIDATE", "RESULT_OPTION")
                }
                .forEach { appendLine(formatEvent(it)) }
        }.trimEnd()
    }

    private fun formatEvent(event: UiTraceEvent): String {
        val context = event.context.toSortedMap()
            .filterNot { (key, _) -> isPrivateKey(key) || key in ENVIRONMENT_KEYS }
            .map { (key, value) -> key + "=" + sanitize(value) }
            .joinToString(" ")
        val prefix = event.sequence.toString() + " | " + formatTime(event.timestampEpochMs) +
            " | " + event.level.name + " | " + event.component + " | " + event.event +
            (event.state?.let { " state=" + it } ?: "")
        return if (context.isBlank()) prefix else prefix + " | " + context
    }

    private fun isPrivateKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US)
        return normalized == "url" || normalized.endsWith("_url") ||
            normalized in setOf("cookie", "cookies", "authorization", "token", "password", "passwd",
                "secret", "api_key", "api-key", "request_headers", "headers", "header_values", "session_id")
    }

    private fun sanitize(value: String): String =
        URL_PATTERN.replace(value, "[URL_REDACTED]").take(1000)

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timestamp))

    private val URL_PATTERN = Regex("(?i)https?://[^\\s]+")
    private val ENVIRONMENT_KEYS = setOf(
        "app_package", "app_version_name", "app_version_code", "app_build_type",
        "app_target_sdk", "app_first_install_ms", "app_last_update_ms",
        "android_sdk", "android_release", "device_manufacturer", "device_model",
        "device_brand", "device_product", "locale", "timezone", "is_24_hour_format",
        "process_id", "available_memory_bytes", "low_memory", "app_uptime_ms",
        "process_uptime_ms", "thread", "thread_id",
    )
}
