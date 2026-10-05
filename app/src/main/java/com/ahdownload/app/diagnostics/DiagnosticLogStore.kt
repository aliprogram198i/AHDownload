package com.ahdownload.app.diagnostics

import android.content.Context
import com.ahdownload.core.common.DiagnosticLevel

/**
 * Backward-compatible facade for older callers.
 * All writes are forwarded to PersistentDiagnosticLogger so the app has one log.
 */
@Deprecated("Use PersistentDiagnosticLogger directly.")
class DiagnosticLogStore(context: Context) {
    private val logger = PersistentDiagnosticLogger(context)

    fun append(type: String, reason: String, detail: String? = null) {
        logger.log(
            level = DiagnosticLevel.ERROR,
            type = type,
            reason = reason,
            operation = "legacy.facade",
            context = detail?.let { mapOf("detail" to it) } ?: emptyMap(),
            throwable = null,
        )
    }

    fun clear() = logger.clear()

    fun exportText(): String = logger.exportText()
}
