package com.ahdownload.app.diagnostics

import android.content.Context
import com.ahdownload.app.BuildConfig
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.UiTraceEvent
import com.ahdownload.core.common.UiTraceLogger
import java.util.UUID

/** UI trace adapter backed by the same SQLite store as pipeline diagnostics. */
class PersistentUiTraceLogger(
    context: Context,
    private val eventStore: DiagnosticEventStore = DiagnosticEventStore(context),
) : UiTraceLogger {
    private val appContext = context.applicationContext
    private val sessionId = eventStore.sessionId
    private val processStartedElapsedMs = android.os.SystemClock.elapsedRealtime()

    override fun record(
        screen: String,
        component: String,
        event: String,
        state: String?,
        context: Map<String, String>,
        level: DiagnosticLevel,
    ) {
        val eventSequence = eventStore.nextSequence()
        val record = UiTraceEvent(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = System.currentTimeMillis(),
            sessionId = sessionId,
            sequence = eventSequence,
            level = level,
            screen = DiagnosticDataSanitizer.sanitizeText(screen),
            component = DiagnosticDataSanitizer.sanitizeText(component),
            event = DiagnosticDataSanitizer.sanitizeText(event),
            state = state?.let(DiagnosticDataSanitizer::sanitizeText),
            context = DiagnosticDataSanitizer.sanitizeContext(
                context + DiagnosticEnvironment.snapshot(appContext) + mapOf(
                    "app_version_name" to BuildConfig.VERSION_NAME,
                    "app_version_code" to BuildConfig.VERSION_CODE.toString(),
                    "diagnostic_session_id" to sessionId,
                    "event_sequence" to eventSequence.toString(),
                    "process_uptime_ms" to (android.os.SystemClock.elapsedRealtime() - processStartedElapsedMs).toString(),
                    "thread" to Thread.currentThread().name,
                ),
            ),
        )
        eventStore.appendUiTrace(record)
    }

    fun list(): List<UiTraceEvent> = eventStore.listUiTraces()
        .sortedByDescending { it.timestampEpochMs }

    /** Clears the same shared store used by pipeline errors. */
    fun clear() = eventStore.clear()

    fun exportText(): String = UiDiagnosticReportFormatter.format(list())

    @Synchronized
    fun exportHomeText(): String {
        val currentUiEvents = list().filter { it.sessionId == sessionId }
        val mirroredPipelineEvents = eventStore.listDiagnostics()
            .asSequence()
            .filter { it.context["diagnostic_session_id"] == sessionId }
            .filter(::shouldMirrorToHomeTrace)
            .map(::asHomeTraceEvent)
            .toList()
        return HomeTraceReportFormatter.format(currentUiEvents + mirroredPipelineEvents)
    }

    fun exportResultCardText(): String = ResultCardTraceReportFormatter.format(list())

    private fun shouldMirrorToHomeTrace(record: DiagnosticLog): Boolean {
        val operation = record.operation
        return operation.startsWith("home.") ||
            operation.startsWith("social.") ||
            operation.startsWith("ui.smart_center.") ||
            operation.startsWith("download.prepare") ||
            operation.startsWith("download.refresh") ||
            operation.startsWith("download.validate") ||
            operation.startsWith("download.queue") ||
            record.type.startsWith("SMART_CENTER")
    }

    private fun asHomeTraceEvent(record: DiagnosticLog): UiTraceEvent {
        val traceContext = buildMap {
            put("diag_type", record.type)
            put("diag_reason", record.reason)
            put("diag_operation", record.operation)
            record.context.forEach { (key, value) ->
                if (key !in TRACE_ENVIRONMENT_KEYS && !SENSITIVE_KEY.containsMatchIn(key)) {
                    put("diag_$key", value)
                }
            }
            record.throwableType?.let { put("diag_exception_type", it) }
            record.throwableMessage?.let { put("diag_exception_message", it) }
        }
        return UiTraceEvent(
            id = "diagnostic-${record.id}",
            timestampEpochMs = record.timestampEpochMs,
            sessionId = sessionId,
            sequence = record.context["event_sequence"]?.toLongOrNull() ?: record.timestampEpochMs,
            level = record.level,
            screen = "HOME",
            component = "pipeline",
            event = "HOME_DIAGNOSTIC",
            state = record.level.name,
            context = DiagnosticDataSanitizer.sanitizeContext(traceContext),
        )
    }

    companion object {
        private val TRACE_ENVIRONMENT_KEYS = setOf(
            "app_package", "app_version_name", "app_version_code", "app_build_type",
            "app_target_sdk", "app_first_install_ms", "app_last_update_ms",
            "android_sdk", "android_release", "device_manufacturer", "device_model",
            "device_brand", "device_product", "locale", "timezone", "is_24_hour_format",
            "process_id", "available_memory_bytes", "low_memory", "app_uptime_ms",
            "diagnostic_session_id", "event_sequence", "process_uptime_ms",
            "thread", "thread_id",
        )
        private val SENSITIVE_KEY = Regex(
            "(cookie|authorization|token|password|passwd|secret|api[_-]?key|session[_-]?id|^url$|_url$|^uri$|_uri$)",
            RegexOption.IGNORE_CASE,
        )
    }
}
