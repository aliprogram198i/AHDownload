package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadScopedDiagnosticLoggerTest {
    @Test
    fun forcesOneStableOperationIdAcrossNestedEventsAndPreservesSourceIds() {
        data class Captured(val type: String, val context: Map<String, String>)
        val events = mutableListOf<Captured>()
        val delegate = DiagnosticLogger { _, type, _, _, context, _ ->
            events += Captured(type, context)
        }
        val logger = DownloadScopedDiagnosticLogger(delegate, "task-123")

        logger.log(
            DiagnosticLevel.INFO,
            "DOWNLOAD_STARTED",
            "started",
            "download.worker",
            mapOf("task_id" to "task-123", "operation_id" to "resolver-op"),
            null,
        )
        logger.log(
            DiagnosticLevel.WARNING,
            "MEDIA_PROBE_ATTEMPT",
            "HTTP_403",
            "download.validate",
            mapOf("candidate_id" to "137"),
            null,
        )

        assertEquals(2, events.size)
        assertEquals(setOf("download:task-123"), events.map { it.context["operation_id"] }.toSet())
        assertEquals(setOf("task-123"), events.map { it.context["task_id"] }.toSet())
        assertEquals(setOf("task-123"), events.map { it.context["download_task_id"] }.toSet())
        assertEquals("resolver-op", events.first().context["source_operation_id"])
        assertEquals("137", events.last().context["candidate_id"])
    }

    @Test
    fun keepsNestedTaskIdentityForDebuggingWithoutBreakingDownloadCorrelation() {
        data class Captured(val context: Map<String, String>)
        var captured: Captured? = null
        val delegate = DiagnosticLogger { _, _, _, _, context, _ -> captured = Captured(context) }
        val logger = DownloadScopedDiagnosticLogger(delegate, "parent-task")

        logger.log(
            DiagnosticLevel.ERROR,
            "AUDIO_STREAM_FAILED",
            "failed",
            "download.audio",
            mapOf("task_id" to "parent-task-audio"),
            null,
        )

        assertEquals("download:parent-task", captured?.context?.get("operation_id"))
        assertEquals("parent-task", captured?.context?.get("task_id"))
        assertEquals("parent-task", captured?.context?.get("download_task_id"))
        assertEquals("parent-task-audio", captured?.context?.get("source_task_id"))
    }
}
