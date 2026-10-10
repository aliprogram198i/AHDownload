package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceEvent
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.designsystem.dispatchDiagnosticAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticControlTracingTest {
    @Test
    fun recordsTapDispatchAndHandlerReturnWithOneAttemptId() {
        val events = mutableListOf<UiTraceEvent>()
        val logger = capture(events)

        var actionRuns = 0
        dispatchDiagnosticAction(logger, "HOME", "HOME.analyze.01", "تحليل") {
            actionRuns += 1
        }

        assertEquals(1, actionRuns)
        assertEquals(
            listOf("CONTROL_TAP_RECEIVED", "ACTION_DISPATCHED", "ACTION_HANDLER_RETURNED"),
            events.map { it.event },
        )
        assertEquals(DiagnosticLevel.INFO, events.last().level)
        assertEquals(1, events.map { it.context["attempt_id"] }.distinct().size)
        assertEquals("HOME.analyze.01", events.first().context["control_id"])
        assertFalse(events.any { it.level == DiagnosticLevel.ERROR })
    }

    @Test
    fun recordsSynchronousHandlerExceptionAndRethrowsIt() {
        val events = mutableListOf<UiTraceEvent>()
        val logger = capture(events)
        val expected = IllegalStateException("simulated")

        val actual = runCatching {
            dispatchDiagnosticAction(logger, "DOWNLOADS", "DOWNLOADS.pause.01", "إيقاف") {
                throw expected
            }
        }.exceptionOrNull()

        assertTrue(actual === expected)
        assertEquals(
            listOf("CONTROL_TAP_RECEIVED", "ACTION_DISPATCHED", "ACTION_HANDLER_THROWN"),
            events.map { it.event },
        )
        assertEquals(DiagnosticLevel.ERROR, events.last().level)
        assertEquals("IllegalStateException", events.last().context["exception_type"])
    }

    private fun capture(events: MutableList<UiTraceEvent>): UiTraceLogger =
        UiTraceLogger { screen, component, event, state, context, level ->
            events += UiTraceEvent(
                id = (events.size + 1).toString(),
                timestampEpochMs = events.size.toLong() + 1L,
                sessionId = "test-session",
                sequence = events.size.toLong() + 1L,
                level = level,
                screen = screen,
                component = component,
                event = event,
                state = state,
                context = context,
            )
        }
}
