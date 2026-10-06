package com.ahdownload.app.diagnostics

import android.content.Context
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.DiagnosticLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import java.util.UUID

/**
 * Single persistent diagnostic sink for the whole application.
 *
 * Security rule: diagnostics never persist credential/cookie/token values and
 * strip URL query/fragment data before storage.
 */
class PersistentDiagnosticLogger(
    context: Context,
) : DiagnosticLogger {
    private val appContext = context.applicationContext
    private val sessionId = UUID.randomUUID().toString()
    private var sequence = 0L
    private val processStartedElapsedMs = android.os.SystemClock.elapsedRealtime()
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<DiagnosticLog>>() {}.type

    init {
        migrateLegacyEntries()
    }

    @Synchronized
    override fun log(
        level: DiagnosticLevel,
        type: String,
        reason: String,
        operation: String,
        context: Map<String, String>,
        throwable: Throwable?,
    ) {
        val record = DiagnosticLog(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = System.currentTimeMillis(),
            level = level,
            type = type,
            reason = sanitizeText(reason),
            operation = sanitizeText(operation),
            context = sanitizeContext(
                context + DiagnosticEnvironment.snapshot(appContext) + mapOf(
                    "diagnostic_session_id" to sessionId,
                    "event_sequence" to (++sequence).toString(),
                    "process_uptime_ms" to (android.os.SystemClock.elapsedRealtime() - processStartedElapsedMs).toString(),
                    "thread" to Thread.currentThread().name,
                    "thread_id" to Thread.currentThread().id.toString(),
                ),
            ),
            throwableType = throwable?.javaClass?.name,
            throwableMessage = throwable?.message?.let(::sanitizeText),
            throwableStackTrace = throwable?.let(::stackTraceText),
        )
        val updated = (read() + record).takeLast(MAX_ENTRIES)
        preferences.edit().putString(KEY_LOGS, gson.toJson(updated, listType)).apply()
    }

    @Synchronized
    fun list(): List<DiagnosticLog> = read().sortedByDescending { it.timestampEpochMs }

    @Synchronized
    fun clear() {
        preferences.edit()
            .remove(KEY_LOGS)
            .remove(LEGACY_KEY_ENTRIES)
            .apply()
    }

    @Synchronized
    fun exportText(): String {
        val logs = list()
        if (logs.isEmpty()) return "AHDownload Diagnostic Report\\nstatus=NO_LOGS"

        val latestError = logs.firstOrNull { it.level == DiagnosticLevel.ERROR }
        val sessionId = latestError?.context?.get("diagnostic_session_id")
        val related = if (sessionId != null) {
            logs.filter { it.context["diagnostic_session_id"] == sessionId }.take(MAX_EXPORT_EVENTS)
        } else {
            logs.take(MAX_EXPORT_EVENTS)
        }

        val anchor = latestError ?: related.first()
        return buildString {
            appendLine("AHDownload Diagnostic Report")
            appendLine("app=" + (anchor.context["app_package"] ?: appContext.packageName) + " version=" + (anchor.context["app_version_name"] ?: "unknown") + " (" + (anchor.context["app_version_code"] ?: "unknown") + ")")
            appendLine("android=" + (anchor.context["android_release"] ?: "unknown") + " sdk=" + (anchor.context["android_sdk"] ?: "unknown") + " targetSdk=" + (anchor.context["app_target_sdk"] ?: "unknown"))
            appendLine("device=" + (anchor.context["device_manufacturer"] ?: "unknown") + " " + (anchor.context["device_model"] ?: "unknown"))
            appendLine("session=" + (sessionId ?: "unknown") + " events=" + related.size)
            appendLine("latest=" + formatTime(anchor.timestampEpochMs) + " level=" + anchor.level + " type=" + anchor.type + " operation=" + anchor.operation)
            appendLine("reason=" + anchor.reason)
            anchor.throwableType?.let { appendLine("exception=" + it) }
            anchor.throwableMessage?.let { appendLine("detail=" + it) }
            anchor.throwableStackTrace?.let { appendLine("stack=" + compactStack(it)) }
            appendLine("timeline:")
            related.asReversed().forEach { log -> appendLine(formatCompactEvent(log)) }
        }.trimEnd()
    }
    companion object {
        private const val PREFS = "ahdownload_diagnostics"
        private const val KEY_LOGS = "logs"
        private const val LEGACY_KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500
        private const val MAX_EXPORT_EVENTS = 36

        private val SENSITIVE_KEY = Regex(
            "(cookie|authorization|token|password|passwd|secret|api[_-]?key|session[_-]?id)",
            RegexOption.IGNORE_CASE,
        )
        private val URL_WITH_QUERY = Regex("(?i)(https?://[^\\s?#]+)[?#][^\\s]*")
    }

    private fun read(): List<DiagnosticLog> {
        val json = preferences.getString(KEY_LOGS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<DiagnosticLog>>(json, listType) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    private fun sanitizeContext(context: Map<String, String>): Map<String, String> =
        context.mapValues { (key, value) ->
            when {
                key == "diagnostic_session_id" ||
                    key == "event_sequence" ||
                    key == "process_uptime_ms" ||
                    key == "thread" ||
                    key == "thread_id" -> sanitizeText(value)
                SENSITIVE_KEY.containsMatchIn(key) -> "[REDACTED]"
                else -> sanitizeText(value)
            }
        }

    private fun formatCompactEvent(log: DiagnosticLog): String {
        val sequence = log.context["event_sequence"] ?: "-"
        val uptime = log.context["process_uptime_ms"] ?: "-"
        val context = compactContext(log.context)
        return buildString {
            append(sequence + " | " + formatTime(log.timestampEpochMs) + " | " + log.level + " | " + log.type + " | " + log.operation + " | " + log.reason)
            if (uptime != "-") append(" | uptimeMs=" + uptime)
            if (context.isNotEmpty()) append(" | " + context)
        }
    }

    private fun compactContext(context: Map<String, String>): String {
        val keys = listOf("source", "provider", "stage", "resolver", "candidate", "status", "http_status", "content_type", "content_length", "duration_ms", "attempt", "attempts", "result", "failure_code")
        return keys.mapNotNull { key -> context[key]?.takeIf { it.isNotBlank() }?.let { key + "=" + it } }.joinToString(" ")
    }

    private fun compactStack(stack: String): String = stack.lineSequence().take(12).joinToString(" <- ").take(2400)

    private fun formatTime(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).toString()
    private fun stackTraceText(throwable: Throwable): String =
        buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < 4) {
                append(current::class.java.name)
                current.message?.let { append(": ").append(sanitizeText(it)) }
                appendLine()
                current.stackTrace.take(24).forEach { appendLine("    at $it") }
                current = current.cause
                depth++
            }
        }.trim().take(12000)

    private fun sanitizeText(value: String): String =
        URL_WITH_QUERY.replace(value, "$1")

    private fun migrateLegacyEntries() {
        synchronized(this) {
            val raw = preferences.getString(LEGACY_KEY_ENTRIES, null) ?: return
            val legacy = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            DiagnosticLog(
                                id = UUID.randomUUID().toString(),
                                timestampEpochMs = item.optLong("timestamp", System.currentTimeMillis()),
                                level = DiagnosticLevel.ERROR,
                                type = item.optString("type", "LEGACY"),
                                reason = sanitizeText(item.optString("reason", "Unknown legacy error")),
                                operation = "legacy",
                                context = emptyMap(),
                                throwableType = null,
                                throwableMessage = item.optString("detail").takeIf { it.isNotBlank() }?.let(::sanitizeText),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())

            if (legacy.isNotEmpty()) {
                val merged = (read() + legacy)
                    .sortedBy { it.timestampEpochMs }
                    .takeLast(MAX_ENTRIES)
                preferences.edit()
                    .putString(KEY_LOGS, gson.toJson(merged, listType))
                    .remove(LEGACY_KEY_ENTRIES)
                    .apply()
            } else {
                preferences.edit().remove(LEGACY_KEY_ENTRIES).apply()
            }
        }
    }
}
