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
    fun exportText(): String = DiagnosticReportFormatter.format(list())

    companion object {
        private const val PREFS = "ahdownload_diagnostics"
        private const val KEY_LOGS = "logs"
        private const val LEGACY_KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500

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
                                throwableMessage = item.optString("detail")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::sanitizeText),
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
