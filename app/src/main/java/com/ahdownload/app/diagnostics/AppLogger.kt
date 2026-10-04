package com.ahdownload.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Privacy-first runtime diagnostics.
 *
 * Logs operational facts, timings, state transitions and failures while
 * redacting URLs, cookies, credentials, tokens and other sensitive material.
 */
object AppLogger {
    private const val FILE_NAME = "ahdownload.log"
    private const val MAX_BYTES = 1024 * 1024L
    private const val MAX_EVENT_DETAILS = 12 * 1024
    private const val MAX_STACKTRACE = 16 * 1024
    private val lock = Any()
    private val runtimeId = UUID.randomUUID().toString().replace("-", "").take(12)

    fun info(context: Context, event: String, details: String = "") =
        write(context, "INFO", event, details)

    fun warn(context: Context, event: String, details: String = "") =
        write(context, "WARN", event, details)

    fun error(context: Context, event: String, throwable: Throwable? = null, details: String = "") {
        val stack = throwable?.stackTraceToString().orEmpty().take(MAX_STACKTRACE)
        val payload = buildString {
            if (details.isNotBlank()) appendLine(details)
            throwable?.javaClass?.name?.takeIf { it.isNotBlank() }?.let { appendLine("exception=$it") }
            throwable?.message?.takeIf { it.isNotBlank() }?.let { appendLine("message=$it") }
            if (stack.isNotBlank()) append(stack)
        }
        write(context, "ERROR", event, payload)
    }

    fun read(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else "AHDownload diagnostic log is empty."
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }

    fun copyText(context: Context): String {
        val header = buildString {
            appendLine("AHDownload Diagnostic Log")
            appendLine("Schema: 3")
            appendLine("App: com.ahdownload.app")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Runtime: $runtimeId")
            appendLine("Time: ${now()}")
            appendLine("Privacy: URLs/cookies/tokens/passwords/credentials redacted")
            appendLine("----")
        }
        return header + read(context)
    }

    fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun write(context: Context, level: String, event: String, details: String) {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val keep = (MAX_BYTES / 2).toInt()
                val text = file.readText()
                file.writeText(text.takeLast(keep))
            }

            val safeEvent = event
                .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                .take(120)
                .ifBlank { "unknown_event" }
            val safeDetails = sanitize(details).take(MAX_EVENT_DETAILS)
            val line = buildString {
                append("[${now()}] [$level] $safeEvent")
                appendLine(" runtime=$runtimeId")
                if (safeDetails.isNotBlank()) appendLine(safeDetails)
            }
            file.appendText(line)
        }
    }

    private fun sanitize(value: String): String {
        var result = value
        // Never persist navigable URLs. Keep only a redacted marker.
        result = result.replace(
            Regex("""(?i)https?://[^\s"'<>]+"""),
            "[URL_REDACTED]"
        )
        // Remove sensitive header/cookie/credential values even when they are
        // not embedded in a URL.
        result = result.replace(
            Regex("""(?im)^(\s*(?:cookie|authorization|proxy-authorization|set-cookie|x-csrftoken|password|passwd|secret|access_token|refresh_token|sessionid|token)\s*[:=]\s*)[^\n]+"""),
            "${'
        )
        result = result.replace(
            Regex("""(?i)([?&](?:sig|signature|token|access_token|auth|expire|expires|key|oe|igshid)=)[^&\s]+"""),
            "$1[REDACTED]"
        )
        return result
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
 }1[REDACTED]"
        )
        result = result.replace(
            Regex("""(?i)([?&](?:sig|signature|token|access_token|auth|expire|expires|key|oe|igshid)=)[^&\s]+"""),
            "$1[REDACTED]"
        )
        return result
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
