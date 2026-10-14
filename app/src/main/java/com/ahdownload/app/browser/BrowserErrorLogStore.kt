package com.ahdownload.app.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class BrowserErrorEntry(
    val timestampEpochMs: Long,
    val type: String,
    val message: String,
    val stage: String,
    val host: String,
    val code: String? = null,
    val severity: String = "ERROR",
    val safeUrl: String? = null,
    val pageTitle: String? = null,
    val details: Map<String, String> = emptyMap(),
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
                val rawDetails = item.optJSONObject("details") ?: JSONObject()
                val parsedDetails = buildMap {
                    val names = rawDetails.names()
                    if (names != null) {
                        for (detailIndex in 0 until names.length()) {
                            val key = names.optString(detailIndex)
                            if (key.isBlank()) continue
                            val value = rawDetails.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: continue
                            put(safeKey(key), safeDetail(key, value))
                        }
                    }
                }
                add(
                    BrowserErrorEntry(
                        timestampEpochMs = item.optLong("time"),
                        type = safe(item.optString("type"), 80),
                        message = safe(item.optString("message"), 300),
                        stage = safe(item.optString("stage"), 80),
                        host = safe(item.optString("host").takeIf { it != "null" } ?: "unknown", 120),
                        code = item.optString("code").takeIf { it.isNotBlank() && it != "null" }?.let { safe(it, 60) },
                        severity = normalizeSeverity(item.optString("severity")),
                        safeUrl = safeUrlOnly(item.optString("safe_url").takeIf { it.isNotBlank() && it != "null" }),
                        pageTitle = item.optString("page_title").takeIf { it.isNotBlank() && it != "null" }?.let { safe(it, 160) },
                        details = parsedDetails,
                    ),
                )
            }
        }.sortedByDescending { it.timestampEpochMs }
    }

    fun record(
        type: String,
        message: String,
        stage: String,
        url: String?,
        code: String? = null,
        severity: String = "ERROR",
        pageTitle: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        val entry = BrowserErrorEntry(
            timestampEpochMs = System.currentTimeMillis(),
            type = safe(type, 80),
            message = safe(message, 300),
            stage = safe(stage, 80),
            host = hostOnly(url),
            code = code?.let { safe(it, 60) },
            severity = normalizeSeverity(severity),
            safeUrl = safeUrlOnly(url),
            pageTitle = pageTitle?.let { safe(it, 160) },
            details = details.entries
                .take(
                    if (type == "PAGE_RENDER_CHECK" || type == "PAGE_RENDER_RECHECK") {
                        MAX_RENDER_DIAGNOSTIC_DETAILS
                    } else {
                        MAX_DETAILS
                    },
                )
                .associate { safeKey(it.key) to safeDetail(it.key, it.value) },
        )
        synchronized(lock) {
            val all = (list() + entry).sortedByDescending { it.timestampEpochMs }.take(MAX_ENTRIES)
            val json = JSONArray()
            all.forEach { item ->
                val detailJson = JSONObject()
                item.details.forEach { (key, value) -> detailJson.put(key, value) }
                json.put(
                    JSONObject()
                        .put("time", item.timestampEpochMs)
                        .put("type", item.type)
                        .put("message", item.message)
                        .put("stage", item.stage)
                        .put("host", item.host)
                        .put("code", item.code ?: "")
                        .put("severity", item.severity)
                        .put("safe_url", item.safeUrl ?: "")
                        .put("page_title", item.pageTitle ?: "")
                        .put("details", detailJson),
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
        appendLine("AHDownload Browser Event Detail")
        appendLine("time_epoch_ms=${entry.timestampEpochMs}")
        appendLine("severity=${entry.severity}")
        appendLine("type=${entry.type}")
        appendLine("stage=${entry.stage}")
        appendLine("host=${entry.host}")
        entry.safeUrl?.let { appendLine("safe_url=$it") }
        entry.pageTitle?.let { appendLine("page_title=$it") }
        entry.code?.let { appendLine("code=$it") }
        appendLine("message=${entry.message}")
        entry.details.toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
        appendLine("query_and_fragment=not_saved")
        appendLine("sensitive_values=redacted")
    }.take(MAX_EXPORT_CHARS)

    fun exportLatestText(): String =
        list().firstOrNull()?.let(::exportEntryText) ?: "AHDownload Browser Event Log\nentries=0"

    fun exportText(): String {
        val entries = list()
        return buildString {
            appendLine("AHDownload Browser Event Log")
            appendLine("entries=${entries.size}")
            entries.forEachIndexed { index, item ->
                appendLine()
                appendLine("#${index + 1}")
                append(exportEntryText(item))
            }
        }.take(MAX_EXPORT_CHARS)
    }

    private fun hostOnly(url: String?): String =
        runCatching { URI(url.orEmpty()).host?.lowercase(Locale.ROOT)?.take(120) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    /** Keep the origin and short path for route correlation, never query parameters or fragments. */
    private fun safeUrlOnly(url: String?): String? = runCatching {
        val uri = URI(url.orEmpty())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) return@runCatching null
        val safePath = uri.rawPath.orEmpty().split('/').joinToString("/") { segment ->
            when {
                segment.length > 48 -> "[path-segment]"
                Regex("""[A-Za-z0-9_-]{32,}""").containsMatchIn(segment) -> "[path-segment]"
                else -> segment.take(48)
            }
        }
        "$scheme://$host$safePath".take(200)
    }.getOrNull()

    private fun safeDetail(key: String, value: String): String =
        if (Regex("""(?i)(cookie|authorization|token|password|passwd|secret|session|csrf|signature|jwt)""")
                .containsMatchIn(key)
        ) "[redacted]" else safe(value, 220)

    private fun normalizeSeverity(raw: String): String =
        raw.uppercase(Locale.ROOT).takeIf { it in SEVERITIES } ?: "ERROR"

    private fun safeKey(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .take(48)
            .ifBlank { "detail" }

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
        const val MAX_ENTRIES = 80
        const val MAX_DETAILS = 30
        const val MAX_RENDER_DIAGNOSTIC_DETAILS = 40
        const val MAX_EXPORT_CHARS = 30_000
        val SEVERITIES = setOf("INFO", "SUCCESS", "WARNING", "ERROR")
    }
}
