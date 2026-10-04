package com.ahdownload.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest

object AppLogger {
    private const val FILE_NAME = "ahdownload.log"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()

    fun info(context: Context, event: String, details: String = "") =
        write(context, "INFO", event, details)

    fun error(context: Context, event: String, throwable: Throwable? = null, details: String = "") {
        val stack = throwable?.stackTraceToString().orEmpty()
        write(context, "ERROR", event, sanitize(listOf(details, throwable?.javaClass?.name.orEmpty(), throwable?.message.orEmpty(), stack).filter { it.isNotBlank() }.joinToString("\n")))
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
            appendLine("App: com.ahdownload.app")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time: ${now()}")
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
                val text = file.readText()
                file.writeText(text.takeLast((MAX_BYTES / 2).toInt()))
            }
            file.appendText(sanitize("[${now()}] [$level] $event${if (details.isBlank()) "" else "\n$details"}\n"))
        }
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("(?i)(cookie|authorization|proxy-authorization)\\s*[:=]\\s*[^\\n]+"), "$1=[REDACTED]")
            .replace(Regex("(?i)([?&](?:sig|signature|token|access_token|auth|expire|expires|key)=)[^&\\s]+"), "$1[REDACTED]")

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
