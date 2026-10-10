package com.ahdownload.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.DropdownMenuItem as MaterialDropdownMenuItem
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.Switch as MaterialSwitch
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceLogger
import java.util.UUID

/** Provided once at the application root. UI modules use it without depending on app code. */
val LocalDiagnosticUiTraceLogger = staticCompositionLocalOf<UiTraceLogger?> { null }

@Composable
fun DiagnosticButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.button",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
    content: @Composable RowScope.() -> Unit,
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(modifier, trackingScreen, trackingId, trackingLabel, enabled, disabledReason)
    MaterialButton(
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
        modifier = tracked,
        enabled = enabled,
        content = content,
    )
}

@Composable
fun DiagnosticOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.outlined_button",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
    content: @Composable RowScope.() -> Unit,
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(modifier, trackingScreen, trackingId, trackingLabel, enabled, disabledReason)
    MaterialOutlinedButton(
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
        modifier = tracked,
        enabled = enabled,
        content = content,
    )
}

@Composable
fun DiagnosticTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.text_button",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
    content: @Composable RowScope.() -> Unit,
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(modifier, trackingScreen, trackingId, trackingLabel, enabled, disabledReason)
    MaterialTextButton(
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
        modifier = tracked,
        enabled = enabled,
        content = content,
    )
}

@Composable
fun DiagnosticIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.icon_button",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
    content: @Composable () -> Unit,
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(modifier, trackingScreen, trackingId, trackingLabel, enabled, disabledReason)
    MaterialIconButton(
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
        modifier = tracked,
        enabled = enabled,
        content = content,
    )
}

@Composable
fun DiagnosticFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.filter_chip",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(modifier, trackingScreen, trackingId, trackingLabel, enabled, disabledReason)
    MaterialFilterChip(
        selected = selected,
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
        label = label,
        modifier = tracked,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

@Composable
fun DiagnosticDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.dropdown_item",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(modifier, trackingScreen, trackingId, trackingLabel, enabled, disabledReason)
    MaterialDropdownMenuItem(
        text = text,
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
        modifier = tracked,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
    )
}

@Composable
fun DiagnosticSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackingScreen: String = "UNKNOWN_SCREEN",
    trackingId: String = "unidentified.switch",
    trackingLabel: String = trackingId,
    disabledReason: String = "callsite_precondition_not_explicit",
) {
    val logger = LocalDiagnosticUiTraceLogger.current
    val effectiveEnabled = enabled && onCheckedChange != null
    val tracked = diagnosticControlModifier(
        modifier,
        trackingScreen,
        trackingId,
        trackingLabel,
        effectiveEnabled,
        if (onCheckedChange == null) "onCheckedChange_callback_missing" else disabledReason,
    )
    MaterialSwitch(
        checked = checked,
        onCheckedChange = { value ->
            dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel) {
                onCheckedChange?.invoke(value)
            }
        },
        modifier = tracked,
        enabled = effectiveEnabled,
    )
}

/**
 * Tracks whether a control is on-screen, enabled state changes, press delivery,
 * callback dispatch/return and synchronous exceptions. Async business outcomes
 * must still be emitted by the operation pipeline.
 */
@Composable
fun diagnosticControlModifier(
    modifier: Modifier,
    trackingScreen: String,
    trackingId: String,
    trackingLabel: String,
    enabled: Boolean,
    disabledReason: String = "callsite_precondition_not_explicit",
): Modifier {
    val logger = LocalDiagnosticUiTraceLogger.current
    val latestLogger by rememberUpdatedState(logger)
    var lastVisibility by remember(trackingScreen, trackingId) { mutableStateOf<Boolean?>(null) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.coerceAtLeast(1).dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.coerceAtLeast(1).dp.toPx() }

    LaunchedEffect(trackingScreen, trackingId, enabled, disabledReason, logger) {
        recordDiagnosticControlEvent(
            logger = latestLogger,
            screen = trackingScreen,
            controlId = trackingId,
            label = trackingLabel,
            event = "CONTROL_STATE_CHANGED",
            state = if (enabled) "ENABLED" else "DISABLED",
            context = mapOf(
                "control_enabled" to enabled.toString(),
                "disabled_reason" to if (enabled) "none" else disabledReason,
            ),
        )
    }

    DisposableEffect(trackingScreen, trackingId, logger) {
        recordDiagnosticControlEvent(
            logger, trackingScreen, trackingId, trackingLabel,
            event = "CONTROL_COMPOSED", state = "COMPOSED",
        )
        onDispose {
            recordDiagnosticControlEvent(
                latestLogger, trackingScreen, trackingId, trackingLabel,
                event = "CONTROL_DISPOSED", state = "DISPOSED",
            )
        }
    }

    return modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val visible = coordinates.isAttached &&
            coordinates.size.width > 0 && coordinates.size.height > 0 &&
            bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < screenWidthPx && bounds.top < screenHeightPx
        if (lastVisibility != visible) {
            lastVisibility = visible
            recordDiagnosticControlEvent(
                logger = latestLogger,
                screen = trackingScreen,
                controlId = trackingId,
                label = trackingLabel,
                event = if (visible) "CONTROL_VISIBLE_ON_SCREEN" else "CONTROL_OUT_OF_VIEW",
                state = if (visible) "VISIBLE" else "OUT_OF_VIEW",
                context = mapOf("control_enabled" to enabled.toString()),
            )
        }
    }
}

@Composable
fun Modifier.tracedClickable(
    trackingScreen: String,
    trackingId: String,
    trackingLabel: String,
    enabled: Boolean = true,
    disabledReason: String = "callsite_precondition_not_explicit",
    onClick: () -> Unit,
): Modifier {
    val logger = LocalDiagnosticUiTraceLogger.current
    val tracked = diagnosticControlModifier(
        this, trackingScreen, trackingId, trackingLabel, enabled, disabledReason,
    )
    return tracked.clickable(
        enabled = enabled,
        onClick = { dispatchDiagnosticAction(logger, trackingScreen, trackingId, trackingLabel, onClick) },
    )
}

fun dispatchDiagnosticAction(
    logger: UiTraceLogger?,
    screen: String,
    controlId: String,
    label: String,
    action: () -> Unit,
) {
    val attemptId = UUID.randomUUID().toString().take(12)
    val baseContext = mapOf(
        "control_id" to controlId,
        "control_label" to label,
        "attempt_id" to attemptId,
    )
    recordDiagnosticControlEvent(logger, screen, controlId, label, "CONTROL_TAP_RECEIVED", "RECEIVED", baseContext)
    recordDiagnosticControlEvent(logger, screen, controlId, label, "ACTION_DISPATCHED", "DISPATCHED", baseContext)
    try {
        action()
        recordDiagnosticControlEvent(
            logger, screen, controlId, label, "ACTION_HANDLER_RETURNED", "RETURNED", baseContext,
        )
    } catch (error: Throwable) {
        recordDiagnosticControlEvent(
            logger = logger,
            screen = screen,
            controlId = controlId,
            label = label,
            event = "ACTION_HANDLER_THROWN",
            state = "THREW",
            context = baseContext + mapOf("exception_type" to error.javaClass.simpleName),
            level = DiagnosticLevel.ERROR,
        )
        throw error
    }
}

fun recordDiagnosticControlEvent(
    logger: UiTraceLogger?,
    screen: String,
    controlId: String,
    label: String,
    event: String,
    state: String,
    context: Map<String, String> = emptyMap(),
    level: DiagnosticLevel = DiagnosticLevel.INFO,
) {
    runCatching {
        logger?.record(
            screen = screen,
            component = "interactive_control",
            event = event,
            state = state,
            context = mapOf(
                "control_id" to controlId,
                "control_label" to label,
            ) + context,
            level = level,
        )
    }
}
