package com.ahdownload.core.common

/**
 * UI-only telemetry intended to reconstruct what was visible and interactive
 * without storing credentials, cookies, tokens, or URL query parameters.
 */
data class UiTraceEvent(
    val id: String,
    val timestampEpochMs: Long,
    val sessionId: String,
    val sequence: Long,
    val level: DiagnosticLevel = DiagnosticLevel.INFO,
    val screen: String,
    val component: String,
    val event: String,
    val state: String? = null,
    val context: Map<String, String> = emptyMap(),
)

fun interface UiTraceLogger {
    fun record(
        screen: String,
        component: String,
        event: String,
        state: String? = null,
        context: Map<String, String> = emptyMap(),
        level: DiagnosticLevel = DiagnosticLevel.INFO,
    )
}