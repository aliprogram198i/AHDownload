package com.ahdownload.core.common

import java.time.Instant

enum class DiagnosticLevel { INFO, WARNING, ERROR }

data class DiagnosticLog(
    val id: String,
    val timestampEpochMs: Long,
    val level: DiagnosticLevel,
    val type: String,
    val reason: String,
    val operation: String,
    val context: Map<String, String> = emptyMap(),
    val throwableType: String? = null,
    val throwableMessage: String? = null,
)

fun interface DiagnosticLogger {
    fun log(
        level: DiagnosticLevel,
        type: String,
        reason: String,
        operation: String,
        context: Map<String, String>,
        throwable: Throwable?,
    )
}
