package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger

/**
 * Adds a stable correlation key to every event emitted by one logical download.
 * The key is task-based, so retries and nested video/audio transfers stay together.
 */
class DownloadScopedDiagnosticLogger(
    private val delegate: DiagnosticLogger,
    taskId: String,
) : DiagnosticLogger {
    private val stableTaskId = taskId.trim().take(MAX_TASK_ID_LENGTH).ifBlank { "unknown" }
    private val operationId = "download:$stableTaskId"

    override fun log(
        level: DiagnosticLevel,
        type: String,
        reason: String,
        operation: String,
        context: Map<String, String>,
        throwable: Throwable?,
    ) {
        val correlatedContext = buildMap {
            putAll(context)
            context["operation_id"]
                ?.takeIf { it.isNotBlank() && it != operationId }
                ?.let { put("source_operation_id", it) }
            context["task_id"]
                ?.takeIf { it.isNotBlank() && it != stableTaskId }
                ?.let { put("source_task_id", it) }

            // Force the same ID even for nested audio/video stream events.
            put("operation_id", operationId)
            put("task_id", stableTaskId)
            put("download_task_id", stableTaskId)
        }
        delegate.log(level, type, reason, operation, correlatedContext, throwable)
    }

    private companion object {
        const val MAX_TASK_ID_LENGTH = 128
    }
}
