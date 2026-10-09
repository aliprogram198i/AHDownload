package com.ahdownload.app.diagnostics

import android.content.Context
import com.ahdownload.app.BuildConfig
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import com.ahdownload.core.common.UiTraceLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Persistent UI trace designed for copy/paste debugging.
 *
 * Security rule: URLs are stored without query/fragment data and sensitive
 * values are redacted before persistence.
 */
class PersistentUiTraceLogger(
    context: Context,
) : UiTraceLogger {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<UiTraceEvent>>() {}.type
    private val sessionId = UUID.randomUUID().toString()
    private var sequence = 0L
    private val processStartedElapsedMs = android.os.SystemClock.elapsedRealtime()

    @Synchronized
    override fun record(
        screen: String,
        component: String,
        event: String,
        state: String?,
        context: Map<String, String>,
        level: DiagnosticLevel,
    ) {
        val record = UiTraceEvent(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = System.currentTimeMillis(),
            sessionId = sessionId,
            sequence = ++sequence,
            level = level,
            screen = sanitizeText(screen),
            component = sanitizeText(component),
            event = sanitizeText(event),
            state = state?.let(::sanitizeText),
            context = sanitizeContext(
                context + DiagnosticEnvironment.snapshot(appContext) + mapOf(
                    "app_version_name" to BuildConfig.VERSION_NAME,
                    "app_version_code" to BuildConfig.VERSION_CODE.toString(),
                    "process_uptime_ms" to (android.os.SystemClock.elapsedRealtime() - processStartedElapsedMs).toString(),
                    "thread" to Thread.currentThread().name,
                ),
            ),
        )
        val updated = (read() + record).takeLast(MAX_EVENTS)
        preferences.edit().putString(KEY_EVENTS, gson.toJson(updated, listType)).apply()
    }

    @Synchronized
    fun list(): List<UiTraceEvent> = read().sortedByDescending { it.timestampEpochMs }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_EVENTS).apply()
    }

    @Synchronized
    fun exportText(): String = UiDiagnosticReportFormatter.format(list())

    fun exportHomeText(): String = HomeTraceReportFormatter.format(list())

    @Synchronized
    fun exportResultCardText(): String = ResultCardTraceReportFormatter.format(list())

    private fun read(): List<UiTraceEvent> {
        val json = preferences.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<UiTraceEvent>>(json, listType) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun sanitizeContext(context: Map<String, String>): Map<String, String> =
        context.mapValues { (key, value) ->
            when {
                SENSITIVE_KEY.containsMatchIn(key) -> "[REDACTED]"
                else -> sanitizeText(value)
            }
        }

    private fun sanitizeText(value: String): String =
        URL_WITH_QUERY.replace(value, "$1").take(MAX_VALUE_LENGTH)

    companion object {
        private const val PREFS = "ahdownload_ui_trace"
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 800
        private const val MAX_VALUE_LENGTH = 4000

        private val SENSITIVE_KEY = Regex(
            "(cookie|authorization|token|password|passwd|secret|api[_-]?key|session[_-]?id)",
            RegexOption.IGNORE_CASE,
        )
        private val URL_WITH_QUERY = Regex("(?i)(https?://[^\\s?#]+)[?#][^\\s]*")
    }
}