package com.ahdownload.app.diagnostics

import android.content.Context
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.DiagnosticLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class PersistentDiagnosticLogger(
    context: Context,
) : DiagnosticLogger {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<DiagnosticLog>>() {}.type

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
            reason = reason,
            operation = operation,
            context = context,
            throwableType = throwable?.javaClass?.name,
            throwableMessage = throwable?.message,
        )
        val updated = (read() + record).takeLast(MAX_ENTRIES)
        preferences.edit().putString(KEY_LOGS, gson.toJson(updated, listType)).apply()
    }

    @Synchronized
    fun list(): List<DiagnosticLog> = read().sortedByDescending { it.timestampEpochMs }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_LOGS).apply()
    }

    companion object {
        private const val PREFS = "ahdownload_diagnostics"
        private const val KEY_LOGS = "logs"
        private const val MAX_ENTRIES = 200
    }

    private fun read(): List<DiagnosticLog> {
        val json = preferences.getString(KEY_LOGS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<DiagnosticLog>>(json, listType) ?: emptyList() }
            .getOrDefault(emptyList())
    }
}
