package com.ahdownload.app.diagnostics

import android.content.Context
import com.ahdownload.app.BuildConfig
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.core.common.UiTraceLogger
import java.util.UUID

/**
 * DiagnosticLogger adapter backed by the shared SQLite diagnostic event store.
 * The optional UI logger parameter is retained for source compatibility with
 * older constructors; pipeline events are joined into reports without mirroring
 * them as duplicate persisted UI events.
 */
class PersistentDiagnosticLogger(
    context: Context,
    @Suppress("UNUSED_PARAMETER") uiTraceLogger: UiTraceLogger? = null,
    private val eventStore: DiagnosticEventStore = DiagnosticEventStore(context),
) : DiagnosticLogger {
    private val appContext = context.applicationContext
    private val sessionId = eventStore.sessionId
    private val processStartedElapsedMs = android.os.SystemClock.elapsedRealtime()

    override fun log(
        level: DiagnosticLevel,
        type: String,
        reason: String,
        operation: String,
        context: Map<String, String>,
        throwable: Throwable?,
    ) {
        val sequence = eventStore.nextSequence()
        val enrichedContext = DiagnosticDataSanitizer.sanitizeContext(
            context + DiagnosticEnvironment.snapshot(appContext) + mapOf(
                "diagnostic_session_id" to sessionId,
                "event_sequence" to sequence.toString(),
                "process_uptime_ms" to (android.os.SystemClock.elapsedRealtime() - processStartedElapsedMs).toString(),
                "thread" to Thread.currentThread().name,
                "thread_id" to Thread.currentThread().id.toString(),
                "app_version_name" to BuildConfig.VERSION_NAME,
                "app_version_code" to BuildConfig.VERSION_CODE.toString(),
            ),
        )
        val record = DiagnosticLog(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = System.currentTimeMillis(),
            level = level,
            type = DiagnosticDataSanitizer.sanitizeText(type),
            reason = DiagnosticDataSanitizer.sanitizeText(reason),
            operation = DiagnosticDataSanitizer.sanitizeText(operation),
            context = enrichedContext,
            throwableType = throwable?.javaClass?.name?.let(DiagnosticDataSanitizer::sanitizeText),
            throwableMessage = throwable?.message?.let(DiagnosticDataSanitizer::sanitizeText),
            throwableStackTrace = throwable?.let(::stackTraceText),
        )
        eventStore.appendDiagnostic(record)
    }

    fun list(): List<DiagnosticLog> = eventStore.listDiagnostics()
        .sortedByDescending { it.timestampEpochMs }

    /** Clears diagnostic and UI events together. */
    fun clear() = eventStore.clear()

    fun exportText(): String = DiagnosticReportFormatter.format(list())

    private fun stackTraceText(throwable: Throwable): String =
        buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < 4) {
                append(current::class.java.name)
                current.message?.let { append(": ").append(DiagnosticDataSanitizer.sanitizeText(it)) }
                appendLine()
                current.stackTrace.take(24).forEach { appendLine("    at $it") }
                current = current.cause
                depth++
            }
        }.trim().take(12_000)
}
