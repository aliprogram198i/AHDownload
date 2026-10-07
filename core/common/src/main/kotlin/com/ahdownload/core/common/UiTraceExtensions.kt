package com.ahdownload.core.common

fun UiTraceLogger.snapshot(
    screen: String,
    component: String,
    components: String,
    stateSummary: String,
    context: Map<String, String> = emptyMap(),
) {
    record(
        screen = screen,
        component = component,
        event = "UI_SNAPSHOT",
        state = "VISIBLE",
        context = context + mapOf(
            "components" to components,
            "state_summary" to stateSummary,
        ),
        level = DiagnosticLevel.INFO,
    )
}

fun UiTraceLogger.interaction(
    screen: String,
    component: String,
    action: String,
    context: Map<String, String> = emptyMap(),
) {
    record(
        screen = screen,
        component = component,
        event = "UI_INTERACTION",
        state = "TRIGGERED",
        context = context + mapOf("action" to action),
        level = DiagnosticLevel.INFO,
    )
}

fun UiTraceLogger.uiError(
    screen: String,
    component: String,
    reason: String,
    context: Map<String, String> = emptyMap(),
    throwable: Throwable? = null,
) {
    record(
        screen = screen,
        component = component,
        event = "UI_ERROR",
        state = "ERROR",
        context = context + mapOf("reason" to reason),
        level = DiagnosticLevel.ERROR,
    )
}