package com.ahdownload.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object ErrorLog {
    private const val PREFS = "ahdownload_error_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private const val MAX_CHARS = 120_000
    private val initialized = AtomicBoolean(false)

    fun install(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(app, "UNCAUGHT_EXCEPTION", "انهيار غير معالج", throwable, "thread=" + thread.name")
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun record(context: Context, type: String, message: String, throwable: Throwable? = null, contextInfo: String? = null) {
        val app = context.applicationContext
        runCatching {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
            val entry = JSONObject().apply {
                put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date()))
                put("type", type)
                put("message", message.take(4000))
                put("context", contextInfo.orEmpty().take(4000))
                put("thread", Thread.currentThread().name)
                put("android", Build.VERSION.RELEASE ?: "unknown")
                put("sdk", Build.VERSION.SDK_INT)
                put("device", Build.MANUFACTURER + " " + Build.MODEL)
                put("uptimeMs", SystemClock.elapsedRealtime())
                if (throwable != null) {
                    put("exception", throwable.javaClass.name)
                    put("stackTrace", stackTrace(throwable).take(12000))
                }
            }
            array.put(entry)
            while (array.length() > MAX_ENTRIES) array.remove(0)
            var serialized = array.toString()
            while (serialized.length > MAX_CHARS && array.length() > 1) {
                array.remove(0)
                serialized = array.toString()
            }
            prefs.edit().putString(KEY_ENTRIES, serialized).apply()
        }
    }

    fun recordFailure(context: Context, type: String, throwable: Throwable, contextInfo: String? = null) {
        record(context, type, throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName, throwable, contextInfo)
    }

    fun entries(context: Context): List<LogEntry> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in array.length() - 1 downTo 0) {
                val o = array.optJSONObject(i) ?: continue
                add(LogEntry(
                    time = o.optString("time"),
                    type = o.optString("type"),
                    message = o.optString("message"),
                    context = o.optString("context"),
                    exception = o.optString("exception"),
                    stackTrace = o.optString("stackTrace"),
                    thread = o.optString("thread"),
                    android = o.optString("android"),
                    device = o.optString("device")
                ))
            }
        }
    }

    fun export(context: Context): String {
        val entries = entries(context)
        val header = buildString {
            appendLine("AHDownload — سجل الأخطاء الحقيقي")
            appendLine("Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            appendLine("App: AHDownload")
            appendLine("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Entries: " + entries.size)
            appendLine("=".repeat(72))
        }
        return header + entries.joinToString("\n\n") { it.toText() }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }

    fun sanitizeUrl(value: String): String = runCatching {
        val uri = java.net.URI(value)
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty().take(180)
        if (host.isBlank()) "<invalid-url>" else host + path
    }.getOrDefault("<invalid-url>")

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    data class LogEntry(
        val time: String, val type: String, val message: String, val context: String,
        val exception: String, val stackTrace: String, val thread: String,
        val android: String, val device: String
    ) {
        fun toText(): String = buildString {
            appendLine("[$time] $type")
            appendLine("message: $message")
            if (context.isNotBlank()) appendLine("context: $context")
            if (exception.isNotBlank()) appendLine("exception: $exception")
            appendLine("thread: $thread")
            appendLine("android: $android")
            appendLine("device: $device")
            if (stackTrace.isNotBlank()) { appendLine("stacktrace:"); appendLine(stackTrace) }
        }.trim()
    }
}
