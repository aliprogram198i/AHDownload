package com.ahdownload.app.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticLogEntry(
    val timestampEpochMs: Long,
    val type: String,
    val reason: String,
    val detail: String? = null,
)

class DiagnosticLogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    fun append(type: String, reason: String, detail: String? = null) {
        val entry = DiagnosticLogEntry(System.currentTimeMillis(), type, reason, detail)
        synchronized(lock) {
            val entries = readLocked().toMutableList()
            entries.add(entry)
            val trimmed = entries.takeLast(MAX_ENTRIES)
            preferences.edit().putString(KEY_ENTRIES, encode(trimmed)).apply()
        }
    }

    fun entries(): List<DiagnosticLogEntry> = synchronized(lock) { readLocked().reversed() }

    fun clear() {
        synchronized(lock) {
            preferences.edit().remove(KEY_ENTRIES).apply()
        }
    }

    fun exportText(): String = entries().joinToString("\n\n") { entry ->
        buildString {
            append("AHDownload Diagnostic Log\n")
            append("Time: ").append(java.time.Instant.ofEpochMilli(entry.timestampEpochMs)).append('\n')
            append("Type: ").append(entry.type).append('\n')
            append("Reason: ").append(entry.reason).append('\n')
            entry.detail?.takeIf { it.isNotBlank() }?.let { append("Detail: ").append(it).append('\n') }
        }.trimEnd()
    }

    private fun readLocked(): List<DiagnosticLogEntry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        DiagnosticLogEntry(
                            timestampEpochMs = item.optLong("timestamp"),
                            type = item.optString("type"),
                            reason = item.optString("reason"),
                            detail = item.optString("detail").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<DiagnosticLogEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("timestamp", entry.timestampEpochMs)
                    .put("type", entry.type)
                    .put("reason", entry.reason)
                    .put("detail", entry.detail ?: ""),
            )
        }
        return array.toString()
    }

    private companion object {
        const val PREFERENCES = "ahdownload_diagnostics"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
    }
}
