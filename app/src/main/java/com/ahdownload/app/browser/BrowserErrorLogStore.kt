package com.ahdownload.app.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class BrowserErrorEntry(
    val timestampEpochMs: Long,
    val type: String,
    val message: String,
    val stage: String,
    val host: String,
    val code: String? = null,
)

class BrowserErrorLogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    fun list(): List<BrowserErrorEntry> = synchronized(lock) {
        val json = runCatching { JSONArray(preferences.getString(KEY_ENTRIES, "[]")) }.getOrNull()
            ?: JSONArray()
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    BrowserErrorEntry(
                        timestampEpochMs = item.optLong("time"),
                        type = safe(item.optString("type"), 80),
                        message = safe(item.optString("message"), 240),
                        stage = safe(item.optString("stage"), 80),
                        host = safe(item.optString("host"), 120),
                        code = item.optString("code").takeIf { it.isNotBlank() && it != "null" }?.let { safe(it, 40) },
                    ),
                )
            }
        }.sortedByDescending { it.timestampEpochMs }
    }

    fun record(type: String, message: String, stage: String, url: String?, code: String? = null) {
        val entry = BrowserErrorEntry(
            timestampEpochMs = System.currentTimeMillis(),
            type = safe(type, 80),
            message = safe(message, 240),
            stage = safe(stage, 80),
            host = hostOnly(url),
            code = code?.let { safe(it, 40) },
        )
        synchronized(lock) {
            val all = (list() + entry).sortedByDescending { it.timestampEpochMs }.take(MAX_ENTRIES)
            val json = JSONArray()
            all.forEach { item ->
                json.put(
                    JSONObject()
                        .put("time", item.timestampEpochMs)
                        .put("type", item.type)
                        .put("message", item.message)
                        .put("stage", item.stage)
                        .put("host", item.host)
                        .put("code", item.code ?: ""),
                )
            }
            preferences.edit().putString(KEY_ENTRIES, json.toString()).apply()
        }
    }

    fun clear() {
        synchronized(lock) {
            preferences.edit().remove(KEY_ENTRIES).apply()
        }
    }

    fun exportEntryText(entry: BrowserErrorEntry): String = buildString {
        appendLine("AHDownload Browser Error Detail")
        appendLine("time_epoch_ms=\${entry.timestampEpochMs}")
        appendLine("type=\${entry.type}")
        appendLine("stage=\${entry.stage}")
        appendLine("host=\${entry.host}")
        entry.code?.let { appendLine("code=\$it") }
        appendLine("message=\${entry.message}")
        appendLine("sensitive_values=redacted")
    }.take(MAX_EXPORT_CHARS)

    fun exportLatestText(): String =
        list().firstOrNull()?.let(::exportEntryText) ?: "AHDownload Browser Error Log\nentries=0"

    fun exportText(): String {
        val entries = list()
        return buildString {
            appendLine("AHDownload Browser Error Log")
            appendLine("entries=" + entries.size)
            entries.forEachIndexed { index, item ->
                appendLine()
                appendLine("#\${index + 1}")
                append(exportEntryText(item))
            }
        }.take(MAX_EXPORT_CHARS)
    }

    private fun hostOnly(url: String?): String =
        runCatching { URI(url.orEmpty()).host?.lowercase()?.take(120) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun safe(value: String, maxLength: Int): String {
        val withoutUrls = value.replace(
            Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE),
            "[url]",
        )
        val withoutSecrets = Regex(
            """(?i)\b(cookie|set-cookie|authorization|proxy-authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|secret|session(?:id)?|csrf(?:token)?|signature|sig|jwt)\b\s*[:=]\s*[^\s,;&]+""",
        ).replace(withoutUrls) { match ->
            match.groupValues[1] + "=[redacted]"
        }
        return withoutSecrets
            .filter { it == '\n' || it == '\t' || it >= ' ' }
            .trim()
            .take(maxLength)
            .ifBlank { "unknown" }
    }

    private companion object {
        const val PREFERENCES = "ahdownload_browser_error_log"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 50
        const val MAX_EXPORT_CHARS = 20_000
    }
}
