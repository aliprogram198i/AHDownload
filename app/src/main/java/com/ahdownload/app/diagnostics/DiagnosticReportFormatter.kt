package com.ahdownload.app.diagnostics

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticReportFormatter {
    private const val DEFAULT_MAX_EVENTS = 120
    private const val DIAGNOSTIC_SCHEMA = 4

    fun format(logs: List<DiagnosticLog>, maxEvents: Int = DEFAULT_MAX_EVENTS): String {
        if (logs.isEmpty()) return "AHDownload Diagnostic Report\nstatus=NO_LOGS"

        val ordered = logs.sortedWith(
            compareByDescending<DiagnosticLog> { it.timestampEpochMs }
                .thenByDescending { eventSequence(it) }
                .thenByDescending { it.id },
        )
        val latestError = ordered.firstOrNull { it.level == DiagnosticLevel.ERROR }
        val sessionId = latestError?.context?.get("diagnostic_session_id")
            ?: ordered.first().context["diagnostic_session_id"]
        val sessionEvents = ordered.filter {
            sessionId == null || it.context["diagnostic_session_id"] == sessionId
        }
        val anchor = latestError ?: sessionEvents.first()
        val operationId = anchor.context["operation_id"]
        val scopedEvents = if (latestError == null) {
            sessionEvents
        } else if (!operationId.isNullOrBlank()) {
            sessionEvents.filter { it.context["operation_id"] == operationId }.ifEmpty { sessionEvents }
        } else {
            // sessionEvents is newest-first. Use the event position, not timestamps alone,
            // so errors recorded in the same millisecond cannot merge two incidents.
            val anchorIndex = sessionEvents.indexOfFirst { it.id == anchor.id }
            if (anchorIndex < 0) {
                sessionEvents
            } else {
                val previousErrorIndex = (anchorIndex + 1 until sessionEvents.size)
                    .firstOrNull { sessionEvents[it].level == DiagnosticLevel.ERROR }
                sessionEvents.subList(anchorIndex, previousErrorIndex ?: sessionEvents.size)
            }
        }
        val chronological = scopedEvents.sortedWith(
            compareBy<DiagnosticLog> { it.timestampEpochMs }
                .thenBy { eventSequence(it) }
                .thenBy { it.id },
        )
        val durationMs = chronological.takeIf { it.size >= 2 }?.let {
            it.last().timestampEpochMs - it.first().timestampEpochMs
        }

        val visible = scopedEvents.count { it.type == "SMART_CENTER_OPTION_VISIBLE" }
        val hidden = scopedEvents.count { it.type == "SMART_CENTER_OPTION_HIDDEN" }
        val validationRejected = scopedEvents
            .filter {
                it.type == "MEDIA_VALIDATION_REJECTED" ||
                    (it.type == "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION" &&
                        it.context["validation_result"] == "invalid")
            }
            .mapNotNull { it.context["candidate_id"] }
            .distinct()
            .size
        val validationAccepted = scopedEvents
            .filter {
                it.type == "MEDIA_VALIDATION_ACCEPTED" ||
                    (it.type == "YOUTUBE_FALLBACK_CANDIDATE_VALIDATION" &&
                        it.context["validation_result"] == "valid")
            }
            .mapNotNull { it.context["candidate_id"] }
            .distinct()
            .size
        val requestEvents = scopedEvents.filter(::isRequestEvent)
        val http403 = requestEvents.count { event -> statusCode(event) == 403 }
        val http4xx = requestEvents.count { event -> statusCode(event) in 400..499 }
        val http5xx = requestEvents.count { event -> statusCode(event) in 500..599 }
        val requestCount = requestEvents.size
        val youtubeEvidence = scopedEvents.lastOrNull {
            it.context.containsKey("browser_media_observed")
        }
        val youtubeBotChallengeObserved = scopedEvents.any {
            it.type == "youtube.bot_challenge_detected"
        }
        val isYouTubeOperation = scopedEvents.any {
            it.context["platform"] == "YouTube" || it.type.startsWith("youtube.", ignoreCase = true)
        }

        val status = if (latestError == null) "OK" else "FAILED"
        val rootCause = if (latestError == null) {
            "NONE"
        } else {
            when {
                (anchor.type == "AUTH_REQUIRED" ||
                    anchor.context["failure_code"] == "AuthenticationRequired") &&
                    isYouTubeOperation && youtubeBotChallengeObserved ->
                    "YOUTUBE_BOT_CHALLENGE"
                anchor.type == "AUTH_REQUIRED" ||
                    anchor.context["failure_code"] == "AuthenticationRequired" ->
                    "AUTHENTICATION_REQUIRED"
                anchor.type == "AUDIO_EXTRACTION_FAILED" -> "AUDIO_EXTRACTION_FAILED"
                anchor.type == "YOUTUBE_SEARCH_FAILED" -> youtubeSearchRootCause(scopedEvents, anchor)
                anchor.type == "DOWNLOAD_DESTINATION_COPY_FAILED" ||
                    anchor.type == "DOWNLOAD_STORAGE_IO_FAILED" ||
                    anchor.type == "DOWNLOAD_LOW_STORAGE_BLOCKED" ||
                    anchor.reason.equals("storage_error", ignoreCase = true) ||
                    anchor.context["failure_code"].equals("storage_error", ignoreCase = true) ->
                    "STORAGE_ERROR"
                anchor.type == "MEDIASTORE_PUBLISH_FAILED" -> "STORAGE_ERROR"
                anchor.context["failure_code"] == "ResolverUnavailable" &&
                    networkFailureCategory(scopedEvents) != null -> networkFailureCategory(scopedEvents)!!
                !anchor.context["failure_code"].isNullOrBlank() -> anchor.context["failure_code"]!!
                statusCode(anchor)?.let { it in 400..599 } == true -> "HTTP_" + statusCode(anchor)
                anchor.type.contains("VALIDATION", ignoreCase = true) -> "MEDIA_VALIDATION_FAILED"
                http403 > 0 -> "HTTP_403"
                else -> anchor.type
            }
        }
        val classification = classify(status, rootCause, anchor)
        val action = recommendedAction(status, classification, rootCause, scopedEvents)
        val pipeline = pipelineStates(scopedEvents, latestError)
        val failure = when {
            latestError == null -> "NONE"
            validationRejected > 0 && validationAccepted == 0 -> "NO_VALID_MEDIA_SOURCE"
            else -> rootCause
        }

        val significant = chronological
            .filterNot {
                it.type == "SMART_CENTER_OPTION_VISIBLE" || it.type == "SMART_CENTER_OPTION_HIDDEN"
            }
            .takeLast(maxEvents.coerceAtLeast(1))

        return buildString {
            appendLine("AHDownload Diagnostic")
            appendLine("diagnostic_schema=$DIAGNOSTIC_SCHEMA")
            appendLine(
                "app=${anchor.context["app_package"] ?: "unknown"} " +
                    "version=${anchor.context["app_version_name"] ?: "unknown"} " +
                    "(${anchor.context["app_version_code"] ?: "unknown"}) " +
                    "build=${anchor.context["app_build_type"] ?: "unknown"}",
            )
            appendLine(
                "android=${anchor.context["android_release"] ?: "unknown"} " +
                    "sdk=${anchor.context["android_sdk"] ?: "unknown"} " +
                    "targetSdk=${anchor.context["app_target_sdk"] ?: "unknown"}",
            )
            appendLine(
                "device=${anchor.context["device_manufacturer"] ?: "unknown"} " +
                    "${anchor.context["device_model"] ?: "unknown"}",
            )
            appendLine("session=${sessionId ?: "unknown"} session_events=${sessionEvents.size} incident_events=${scopedEvents.size}")
            operationId?.let { appendLine("operation_id=$it") }
            (anchor.context["download_task_id"] ?: anchor.context["task_id"])
                ?.let { appendLine("download_task_id=$it") }
            durationMs?.let { appendLine("duration_ms=$it") }

            appendLine()
            appendLine("RESULT")
            appendLine("status=$status")
            appendLine("stage=${stageOf(anchor)}")
            appendLine("classification=$classification")
            appendLine("root_cause=$rootCause")
            appendLine("failure=$failure")
            appendLine("action=$action")

            if (latestError != null) {
                appendLine()
                appendLine("INCIDENT")
                appendLine("timestamp=${formatTime(latestError.timestampEpochMs)}")
                appendLine("error_type=${latestError.type}")
                appendLine("reason=${latestError.reason}")
                appendLine("operation=${latestError.operation}")
                appendLine("what_happened=${whatHappened(rootCause, failure, latestError)}")
                latestError.throwableType?.let { appendLine("exception_type=$it") }
                latestError.throwableMessage?.let { appendLine("exception_message=$it") }
                latestError.throwableStackTrace?.let {
                    appendLine("stack_trace:")
                    appendLine(it)
                }
                appendLine("error_context:")
                formatContext(latestError.context).forEach { appendLine("  $it") }
            }

            appendLine()
            appendLine("PIPELINE")
            pipeline.forEach { (name, value) -> appendLine("$name=$value") }

            appendLine()
            appendLine("NETWORK")
            appendLine("requests=$requestCount")
            appendLine("http_403=$http403")
            appendLine("http_4xx=$http4xx")
            appendLine("http_5xx=$http5xx")

            appendLine()
            appendLine("MEDIA")
            appendLine("candidates=${candidateCount(scopedEvents)}")
            appendLine("accepted=$validationAccepted")
            appendLine("rejected=$validationRejected")
            appendLine("selected=${selectedCount(scopedEvents)}")

            youtubeEvidence?.let { evidence ->
                appendLine()
                appendLine("YOUTUBE")
                appendLine("browser_media_observed=${evidence.context["browser_media_observed"] ?: "unknown"}")
                appendLine("browser_request_headers_captured=${evidence.context["browser_request_headers_captured"] ?: "unknown"}")
                appendLine("browser_po_token_observed=${evidence.context["browser_po_token_observed"] ?: evidence.context["po_token_observed"] ?: "unknown"}")
            }

            if (visible > 0 || hidden > 0) {
                appendLine()
                appendLine("SMART_CENTER")
                appendLine("options_extracted=${visible + hidden}")
                appendLine("visible=$visible")
                appendLine("hidden=$hidden")
            }

            appendLine()
            appendLine("FAILURE_CHAIN")
            appendLine(buildFailureChain(scopedEvents, latestError, validationRejected, validationAccepted))

            appendLine()
            appendLine("TIMELINE")
            significant.forEach { appendLine(formatEvent(it)) }
            if (significant.isEmpty()) appendLine("- no significant events")
        }.trimEnd()
    }

    private fun youtubeSearchRootCause(
        events: List<DiagnosticLog>,
        error: DiagnosticLog,
    ): String {
        val request = events.lastOrNull {
            it.type == "HTTP_REQUEST" && it.context["stage"] == "SEARCH"
        }
        val statusCode = request?.let(::statusCode)
        if (statusCode != null && statusCode in 400..599) return "HTTP_$statusCode"

        if (request?.context?.get("request_outcome") == "transport_error") {
            val exceptionType = request.throwableType.orEmpty()
            return when {
                "SocketTimeoutException" in exceptionType || "InterruptedIOException" in exceptionType -> "TIMEOUT"
                "UnknownHostException" in exceptionType -> "DNS_FAILURE"
                "SSLHandshakeException" in exceptionType || "SSLException" in exceptionType -> "TLS_FAILURE"
                "ConnectException" in exceptionType -> "CONNECTION_FAILURE"
                "SocketException" in exceptionType -> "CONNECTION_RESET"
                else -> "IO_FAILURE"
            }
        }

        when (error.context["payload_fallback_failure_detail"]) {
            "http_403" -> return "HTTP_403"
            "http_429" -> return "HTTP_429"
            "http_5xx" -> return "YOUTUBE_INNERTUBE_HTTP_5XX"
            "timeout" -> return "TIMEOUT"
            "dns_failure" -> return "DNS_FAILURE"
            "io_failure" -> return "IO_FAILURE"
            "api_key_missing" -> return "YOUTUBE_INNERTUBE_API_KEY_MISSING"
            "client_version_missing" -> return "YOUTUBE_INNERTUBE_CLIENT_CONFIG_MISSING"
            "response_json_invalid" -> return "YOUTUBE_INNERTUBE_RESPONSE_JSON_INVALID"
            "response_shape_unexpected" -> return "YOUTUBE_INNERTUBE_RESPONSE_SHAPE_UNEXPECTED"
            "request_failed" -> return "YOUTUBE_INNERTUBE_REQUEST_FAILED"
        }

        when (error.context["payload_expression_failure_detail"]) {
            "unresolved_static_identifier" ->
                return "YOUTUBE_SEARCH_PAYLOAD_REFERENCE_UNRESOLVED"
            "static_identifier_declared_in_other_script" ->
                return "YOUTUBE_SEARCH_PAYLOAD_CROSS_SCRIPT_REFERENCE"
            "static_identifier_initializer_unsupported" ->
                return "YOUTUBE_SEARCH_PAYLOAD_DYNAMIC_REFERENCE"
            "static_identifier_suffix_or_delimiter_unsupported" ->
                return "YOUTUBE_SEARCH_PAYLOAD_REFERENCE_EXPRESSION_UNSUPPORTED"
            "unsupported_nested_function_call", "unsupported_member_expression" ->
                return "YOUTUBE_SEARCH_PAYLOAD_EXPRESSION_UNSUPPORTED"
            "dynamic_template_literal_unsupported" ->
                return "YOUTUBE_SEARCH_DYNAMIC_TEMPLATE_UNSUPPORTED"
            "unsupported_json_parse_second_argument" ->
                return "YOUTUBE_SEARCH_JSON_PARSE_ARGUMENTS_UNSUPPORTED"
            "json_parse_call_unterminated" ->
                return "YOUTUBE_SEARCH_JSON_PARSE_CALL_MALFORMED"
            "quoted_string_decode_failed_or_unexpected_suffix" ->
                return "YOUTUBE_SEARCH_STRING_EXPRESSION_MALFORMED"
            "decoded_json_syntax_invalid" ->
                return "YOUTUBE_SEARCH_JSON_SYNTAX_INVALID"
            "decoded_json_root_shape_unexpected" ->
                return "YOUTUBE_SEARCH_ROOT_SHAPE_UNEXPECTED"
            "unsupported_expression_shape" ->
                return "YOUTUBE_SEARCH_PAYLOAD_EXPRESSION_UNSUPPORTED"
        }

        return when (error.context["payload_failure_reason"]) {
            "bot_challenge" -> "YOUTUBE_BOT_CHALLENGE"
            "consent_or_privacy_gate" -> "YOUTUBE_CONSENT_OR_PRIVACY_GATE"
            "initial_data_parse_failed" -> "YOUTUBE_SEARCH_PAYLOAD_MALFORMED"
            "initial_data_marker_missing" -> "YOUTUBE_SEARCH_PAYLOAD_MARKER_MISSING"
            "empty_response" -> "YOUTUBE_SEARCH_EMPTY_RESPONSE"
            else -> if (
                error.throwableMessage?.contains("payload unavailable", ignoreCase = true) == true
            ) {
                "YOUTUBE_SEARCH_PAYLOAD_UNAVAILABLE"
            } else {
                "YOUTUBE_SEARCH_FAILED"
            }
        }
    }

    private fun classify(status: String, rootCause: String, log: DiagnosticLog): String =
        if (status == "OK") {
            if (log.type.contains("SMART_CENTER", ignoreCase = true)) "UI_FLOW" else "COMPLETED"
        } else when {
            rootCause.startsWith("HTTP_") || isNetworkFailureCause(rootCause) -> "NETWORK"
            rootCause == "YOUTUBE_BOT_CHALLENGE" -> "UPSTREAM_CHALLENGE"
            rootCause == "YOUTUBE_CONSENT_OR_PRIVACY_GATE" -> "UPSTREAM_GATE"
            log.type == "YOUTUBE_SEARCH_FAILED" -> "YOUTUBE_SEARCH"
            rootCause == "AUTHENTICATION_REQUIRED" -> "AUTHENTICATION"
            rootCause == "AUDIO_EXTRACTION_FAILED" -> "AUDIO_PROCESSING"
            rootCause == "STORAGE_ERROR" -> "STORAGE"
            rootCause.contains("VALIDATION", ignoreCase = true) || rootCause.contains("MEDIA", ignoreCase = true) -> "MEDIA_VALIDATION"
            log.type.contains("RESOLVER", ignoreCase = true) -> "MEDIA_RESOLUTION"
            log.type.contains("SMART_CENTER", ignoreCase = true) -> "UI_FLOW"
            else -> "INTERNAL"
        }

    private fun recommendedAction(
        status: String,
        classification: String,
        rootCause: String,
        events: List<DiagnosticLog>,
    ): String {
        if (status == "OK") return "NONE"
        val isYouTube = events.any {
            it.context["platform"] == "YouTube" || it.type.startsWith("youtube.", ignoreCase = true)
        }
        val captureEvidence = events.lastOrNull { it.context.containsKey("browser_media_observed") }
        val gvsEvidence = events.lastOrNull { it.type == "youtube.gvs_strategy" }
        val payloadExpressionFailureDetail = events.lastOrNull {
            it.type == "YOUTUBE_SEARCH_FAILED"
        }?.context?.get("payload_expression_failure_detail")
        val payloadFallbackFailureDetail = events.lastOrNull {
            it.type == "YOUTUBE_SEARCH_FAILED"
        }?.context?.get("payload_fallback_failure_detail")
        val explicitUnavailablePlayback = events.any { event ->
            val reason = event.reason.lowercase()
            event.type == "youtube_player_no_candidates" &&
                ("video unavailable" in reason || "video is unavailable" in reason)
        }
        return when {
            payloadFallbackFailureDetail == "api_key_missing" ||
                payloadFallbackFailureDetail == "client_version_missing" ->
                "INSPECT_YOUTUBE_INNERTUBE_PAGE_CONFIGURATION"
            payloadFallbackFailureDetail == "response_json_invalid" ||
                payloadFallbackFailureDetail == "response_shape_unexpected" ->
                "INSPECT_YOUTUBE_INNERTUBE_SEARCH_RESPONSE"
            payloadFallbackFailureDetail == "http_403" ||
                payloadFallbackFailureDetail == "http_429" ||
                payloadFallbackFailureDetail == "http_5xx" ->
                "INSPECT_YOUTUBE_INNERTUBE_HTTP_RESPONSE"
            payloadFallbackFailureDetail == "timeout" ||
                payloadFallbackFailureDetail == "dns_failure" ||
                payloadFallbackFailureDetail == "io_failure" ->
                "INSPECT_YOUTUBE_INNERTUBE_NETWORK"
            payloadExpressionFailureDetail == "unresolved_static_identifier" ->
                "INSPECT_YOUTUBE_SEARCH_STATIC_DATA_REFERENCE"
            payloadExpressionFailureDetail == "static_identifier_declared_in_other_script" ->
                "INSPECT_YOUTUBE_SEARCH_CROSS_SCRIPT_DATA_REFERENCE"
            payloadExpressionFailureDetail == "static_identifier_initializer_unsupported" ->
                "INSPECT_YOUTUBE_SEARCH_DYNAMIC_DATA_INITIALIZER"
            payloadExpressionFailureDetail == "static_identifier_suffix_or_delimiter_unsupported" ->
                "INSPECT_YOUTUBE_SEARCH_DATA_REFERENCE_SYNTAX"
            payloadExpressionFailureDetail == "unsupported_nested_function_call" ||
                payloadExpressionFailureDetail == "unsupported_member_expression" ->
                "INSPECT_YOUTUBE_SEARCH_JSON_PARSE_WRAPPER"
            payloadExpressionFailureDetail == "dynamic_template_literal_unsupported" ->
                "INSPECT_YOUTUBE_SEARCH_DYNAMIC_TEMPLATE_EXPRESSION"
            payloadExpressionFailureDetail == "unsupported_json_parse_second_argument" ->
                "INSPECT_YOUTUBE_SEARCH_JSON_PARSE_ARGUMENTS"
            payloadExpressionFailureDetail == "json_parse_call_unterminated" ||
                payloadExpressionFailureDetail == "quoted_string_decode_failed_or_unexpected_suffix" ->
                "INSPECT_YOUTUBE_SEARCH_EXPRESSION_SYNTAX"
            payloadExpressionFailureDetail == "decoded_json_syntax_invalid" ->
                "INSPECT_YOUTUBE_SEARCH_DECODED_JSON_SYNTAX"
            payloadExpressionFailureDetail == "decoded_json_root_shape_unexpected" ->
                "INSPECT_YOUTUBE_SEARCH_DECODED_JSON_ROOT"
            rootCause == "YOUTUBE_BOT_CHALLENGE" && isYouTube ->
                "REFRESH_YOUTUBE_SESSION_AFTER_BOT_CHECK"
            rootCause == "YOUTUBE_CONSENT_OR_PRIVACY_GATE" && isYouTube ->
                "INSPECT_YOUTUBE_CONSENT_OR_PRIVACY_GATE"
            rootCause in setOf(
                "YOUTUBE_SEARCH_PAYLOAD_MALFORMED",
                "YOUTUBE_SEARCH_PAYLOAD_MARKER_MISSING",
                "YOUTUBE_SEARCH_EMPTY_RESPONSE",
            ) && isYouTube -> "INSPECT_YOUTUBE_SEARCH_RESPONSE_VARIANT"
            classification == "AUTHENTICATION" && isYouTube ->
                "COMPLETE_YOUTUBE_AGE_VERIFICATION"
            isYouTube && explicitUnavailablePlayback ->
                "INSPECT_YOUTUBE_PLAYABILITY_OR_VIDEO_ACCESS"
            isYouTube &&
                gvsEvidence?.context?.get("po_token_observed") == "false" &&
                gvsEvidence.context["browser_media_observed"]?.toIntOrNull()?.let { it > 0 } == true ->
                "INSPECT_YOUTUBE_PO_TOKEN_OR_CLIENT_POLICY"
            classification == "NETWORK" && rootCause == "HTTP_403" && isYouTube &&
                captureEvidence?.context["browser_media_observed"] == "0" ->
                "INSPECT_BROWSER_MEDIA_CAPTURE"
            classification == "NETWORK" && isYouTube &&
                events.any { it.type == "YOUTUBE_SEARCH_FAILED" } -> "INSPECT_YOUTUBE_SEARCH_REQUEST"
            classification == "NETWORK" -> "INSPECT_REQUEST_CONTEXT"
            classification == "YOUTUBE_SEARCH" -> "INSPECT_YOUTUBE_SEARCH_RESPONSE"
            classification == "MEDIA_RESOLUTION" -> "INSPECT_RESOLVER"
            classification == "MEDIA_VALIDATION" -> "INSPECT_VALIDATION"
            classification == "AUDIO_PROCESSING" -> "INSPECT_AUDIO_PROCESSOR"
            classification == "STORAGE" -> "INSPECT_STORAGE"
            classification == "UI_FLOW" -> "INSPECT_UI_FLOW"
            rootCause == "UNHANDLED_EXCEPTION" -> "INSPECT_STACKTRACE"
            else -> "INSPECT_FAILURE_CHAIN"
        }
    }

    private fun pipelineStates(
        events: List<DiagnosticLog>,
        latestError: DiagnosticLog?,
    ): LinkedHashMap<String, String> {
        fun has(type: String) = events.any { it.type == type }
        val latestErrorStage = latestError?.let(::stageOf)
        return linkedMapOf(
            "input" to (events.firstNotNullOfOrNull { it.context["input_type"] } ?: "RECEIVED"),
            "resolution" to when {
                // The latest error determines the failed phase. Earlier authentication or
                // resolver warnings must not mask a later successful resolution.
                latestErrorStage == "RESOLUTION" -> "FAILED"
                has("SMART_CENTER_RESULT_READY") ||
                    has("MEDIA_RESOLUTION_COMPLETED") ||
                    has("RESOLUTION_COMPLETED") ||
                    events.any {
                        it.type == "SMART_CENTER_RESULT_PRESENTED" &&
                            it.context["layout_mode"] == "RESULT_READY"
                    } ||
                    events.any { it.type == "YOUTUBE_CANDIDATE_REFRESH_RESULT" } ||
                    events.any { it.type == "youtube.webview_source_selected" } -> "COMPLETED"
                events.any {
                    it.type.contains("RESOLVER", ignoreCase = true) ||
                        it.operation.contains("resolve", ignoreCase = true) ||
                        it.type.startsWith("youtube.", ignoreCase = true)
                } -> "STARTED"
                else -> "NOT_STARTED"
            },
            "ordering" to if (has("SMART_CENTER_ORDERING")) "COMPLETED" else "NOT_STARTED",
            "presentation" to if (has("SMART_CENTER_RESULT_PRESENTED")) "COMPLETED" else "NOT_STARTED",
            "media_validation" to when {
                events.any { it.type == "MEDIA_VALIDATION_ACCEPTED" || it.type == "MEDIA_VALIDATION_REJECTED" } -> "COMPLETED"
                events.any { it.type.contains("MEDIA_VALIDATION", ignoreCase = true) } -> "STARTED"
                else -> "NOT_STARTED"
            },
            "download" to when {
                events.any { it.type.contains("DOWNLOAD_COMPLETED", ignoreCase = true) } ||
                    has("DOWNLOAD_DESTINATION_COMMITTED") ||
                    has("DOWNLOAD_DESTINATION_COPY_FAILED") ||
                    has("AUDIO_EXTRACTION_COMPLETED") ||
                    has("MEDIASTORE_PUBLISH_FAILED") -> "COMPLETED"
                events.any { it.operation.contains("download", ignoreCase = true) && it.type.contains("DOWNLOAD", ignoreCase = true) } -> "STARTED"
                else -> "NOT_STARTED"
            },
        )
    }

    private fun candidateCount(events: List<DiagnosticLog>): Int =
        events.mapNotNull { event ->
            event.context["candidate_id"]?.takeIf(String::isNotBlank)
        }.distinct().size

    private fun isRequestEvent(event: DiagnosticLog): Boolean =
        event.type == "MEDIA_PROBE_ATTEMPT" ||
            event.type == "HTTP_REQUEST" ||
            event.type == "DOWNLOAD_HTTP_RESPONSE" ||
            (event.type.endsWith("_REQUEST") &&
                event.type != "MEDIA_VALIDATION_PROBE_RESULT" &&
                event.type != "YOUTUBE_CANDIDATE_REFRESH_RESULT")

    private fun selectedCount(events: List<DiagnosticLog>): Int =
        events.mapNotNull { event ->
            val selected = event.type.contains("SELECTED", ignoreCase = true) || event.type == "MEDIA_SOURCE_SELECTED"
            if (selected) event.context["candidate_id"] else null
        }.distinct().size

    private fun statusCode(event: DiagnosticLog): Int? =
        (event.context["http_status"] ?: event.context["status_code"])?.toIntOrNull()
    private fun stageOf(log: DiagnosticLog): String =
        log.context["stage"] ?: when {
            log.type == "AUTH_REQUIRED" ||
                log.context["failure_code"] == "AuthenticationRequired" -> "RESOLUTION"
            log.type.contains("VALIDATION", ignoreCase = true) -> "MEDIA_VALIDATION"
            log.type.contains("RESOLVER", ignoreCase = true) -> "RESOLUTION"
            log.type.contains("SMART_CENTER", ignoreCase = true) -> "SMART_CENTER"
            log.operation.startsWith("download") -> "DOWNLOAD"
            log.operation.startsWith("home") -> "HOME"
            else -> log.operation
        }

    private fun buildFailureChain(
        events: List<DiagnosticLog>,
        latestError: DiagnosticLog?,
        rejected: Int,
        accepted: Int,
    ): String {
        if (latestError == null) return "NONE"

        if (latestError.type == "YOUTUBE_SEARCH_FAILED") {
            val request = events.lastOrNull {
                it.type == "HTTP_REQUEST" && it.context["stage"] == "SEARCH"
            }
            val code = request?.let(::statusCode)
            return when {
                code != null && code in 400..599 -> "search -> http_request_failed:HTTP_$code"
                request?.context?.get("request_outcome") == "transport_error" ->
                    "search -> network_transport_failed"
                latestError.context["payload_fallback_attempted"] == "true" -> {
                    val detail = latestError.context["payload_fallback_failure_detail"].orEmpty()
                    "search -> html_payload_unavailable -> innertube_fallback_failed:$detail"
                }
                latestError.context["payload_failure_reason"] != null -> {
                    val reason = latestError.context["payload_failure_reason"].orEmpty()
                    val expressionDetail =
                        latestError.context["payload_expression_failure_detail"]
                    if (!expressionDetail.isNullOrBlank() && expressionDetail != "unknown") {
                        "search -> response_payload_unavailable:$reason -> payload_expression:$expressionDetail"
                    } else {
                        "search -> response_payload_unavailable:$reason"
                    }
                }
                latestError.throwableMessage?.contains("payload unavailable", ignoreCase = true) == true ->
                    "search -> response_payload_unavailable"
                else -> "search -> request_or_response_failed"
            }
        }

        val networkFailure = networkFailureCategory(events)
        if (latestError.context["failure_code"] == "ResolverUnavailable" && networkFailure != null) {
            return "resolver -> page_request_failed:$networkFailure -> resolution_failed_without_candidates"
        }

        // Destination failures happen after resolution, validation, and local output creation.
        // Do not report an earlier successful validation phase as the failing stage.
        when (latestError.type) {
            "AUTH_REQUIRED" -> return "resolver -> authentication_required"
            "DOWNLOAD_DESTINATION_COPY_FAILED" -> {
                val parts = mutableListOf<String>()
                if (accepted > 0) parts += "validation_passed"
                parts += "download_completed_locally"
                if (events.any { it.type == "AUDIO_EXTRACTION_COMPLETED" }) {
                    parts += "audio_extraction_completed"
                }
                parts += "destination_copy_failed"
                return parts.joinToString(" -> ")
            }
            "MEDIASTORE_PUBLISH_FAILED" -> {
                val parts = mutableListOf<String>()
                if (accepted > 0) parts += "validation_passed"
                parts += "download_completed_locally"
                if (events.any { it.type == "AUDIO_EXTRACTION_COMPLETED" }) {
                    parts += "audio_extraction_completed"
                }
                parts += "mediastore_publish_failed"
                return parts.joinToString(" -> ")
            }
        }

        val parts = mutableListOf<String>()
        val hasResolverEvidence = events.any {
            it.type.contains("RESOLVER", ignoreCase = true) ||
                it.operation.contains("resolve", ignoreCase = true) ||
                it.type.startsWith("youtube.", ignoreCase = true) ||
                it.context["platform"] == "YouTube"
        }
        if (hasResolverEvidence) parts += "resolver"

        // Event names such as "youtube_player_no_candidates" describe an attempted
        // extraction, not a media candidate that actually exists.
        val hasCandidateEvidence = candidateCount(events) > 0 || events.any { event ->
            when (event.type) {
                "youtube.webview_candidates",
                "youtube.webview_source_selected" ->
                    (event.context["candidates"]?.toIntOrNull() ?: 0) > 0 ||
                        (event.context["browser_candidates"]?.toIntOrNull() ?: 0) > 0 ||
                        (event.context["aligned_candidates"]?.toIntOrNull() ?: 0) > 0
                else -> false
            }
        }
        if (hasCandidateEvidence) {
            parts += "candidate"
        } else if (
            latestError.context["failure_code"] == "NoCandidates" ||
            events.any {
                it.type == "youtube_player_no_candidates" ||
                    it.context["failure_code"] == "NoCandidates"
            }
        ) {
            parts += "no_candidates_extracted"
        } else if (hasResolverEvidence && candidateCount(events) == 0 &&
            latestError.context["failure_code"] in setOf("ResolverUnavailable", "ResolverTimeout")) {
            parts += "resolution_failed_without_candidates"
        }

        if (events.any { it.type.contains("VALIDATION", ignoreCase = true) }) parts += "validation"
        if (rejected > 0) parts += "candidate_rejected"
        if (accepted == 0 && rejected > 0) parts += "no_valid_source"
        if (latestError.type.contains("SMART_CENTER", ignoreCase = true)) parts += "ui_error"
        return if (parts.isEmpty()) (latestError.context["failure_code"] ?: latestError.type)
        else parts.distinct().joinToString(" -> ")
    }

    private fun networkFailureCategory(events: List<DiagnosticLog>): String? {
        val allowed = setOf(
            "TIMEOUT", "DNS_FAILURE", "TLS_FAILURE", "CONNECTION_FAILURE",
            "CONNECTION_RESET", "SOCKET_FAILURE", "IO_FAILURE",
        )
        return events.asSequence()
            .filter { event ->
                (event.type == "HTTP_REQUEST" && event.context["network_outcome"] == "FAILED") ||
                    (event.type == "WEB_VIDEO_PAGE_FAILED" &&
                        event.context["failure_stage"] in setOf("PAGE_FETCH", "STATIC_MEDIA_PROBE", "BROWSER_MEDIA_PROBE"))
            }
            .sortedWith(
                compareBy<DiagnosticLog> { it.timestampEpochMs }
                    .thenBy { eventSequence(it) },
            )
            .mapNotNull { it.context["failure_category"] }
            .lastOrNull { it in allowed }
    }

    private fun isNetworkFailureCause(value: String): Boolean = value in setOf(
        "TIMEOUT", "DNS_FAILURE", "TLS_FAILURE", "CONNECTION_FAILURE",
        "CONNECTION_RESET", "SOCKET_FAILURE", "IO_FAILURE",
    )

    private fun formatEvent(log: DiagnosticLog): String {
        val context = formatContext(log.context).joinToString(" ")
        val sequence = log.context["event_sequence"] ?: "-"
        val base =
            "$sequence | ${formatTime(log.timestampEpochMs)} | ${log.level} | " +
                "${log.type} | ${log.operation} | ${log.reason}"
        return if (context.isBlank()) base else "$base | $context"
    }

    private fun formatContext(context: Map<String, String>): List<String> =
        context
            .filterKeys { !isEnvironmentKey(it) }
            .toSortedMap()
            .mapNotNull { (key, value) ->
                value.takeIf { it.isNotBlank() }?.let { "$key=$it" }
            }

    private fun isEnvironmentKey(key: String): Boolean = key in setOf(
        "app_package", "app_version_name", "app_version_code", "app_build_type",
        "app_target_sdk", "app_first_install_ms", "app_last_update_ms",
        "android_sdk", "android_release", "device_manufacturer", "device_model",
        "device_brand", "device_product", "locale", "timezone", "is_24_hour_format",
        "process_id", "available_memory_bytes", "low_memory", "app_uptime_ms",
        "diagnostic_session_id", "event_sequence", "process_uptime_ms",
        "thread", "thread_id", "operation_id",
    )

    private fun whatHappened(rootCause: String, failure: String, error: DiagnosticLog): String =
        when {
            failure == "NO_VALID_MEDIA_SOURCE" ->
                "تم تحليل الرابط واستخراج المرشحين، ثم رُفضت المصادر أثناء التحقق حتى انتهى المسار دون مصدر وسائط صالح."
            rootCause == "HTTP_403" ->
                "وصل الطلب إلى مرحلة جلب أو التحقق من المصدر، لكن الخادم رفض الطلب بـ HTTP 403، ثم انتهى المسار الحالي قبل بدء تنزيل صالح."
            error.type == "YOUTUBE_SEARCH_FAILED" &&
                error.context["payload_fallback_attempted"] == "true" &&
                error.context["payload_fallback_outcome"] == "failed" ->
                "تعذّر استخراج نتائج من حمولة صفحة YouTube، ثم فشل مسار Innertube الاحتياطي. راجع payload_fallback_failure_detail لمعرفة هل السبب إعدادات الصفحة أو HTTP أو الشبكة أو بنية الاستجابة."
            error.type == "YOUTUBE_SEARCH_FAILED" &&
                error.context["payload_parser_failure_stage"] == "expression_decode" ->
                "وصلت استجابة بحث YouTube بنجاح على مستوى HTTP، لكن تعذّر تفسير تعبير ytInitialData. حقول payload_expression_* تحدد شكل التعبير وموضعه دون حفظ محتوى الاستجابة."
            error.type == "YOUTUBE_SEARCH_FAILED" &&
                error.context["payload_parser_failure_stage"] == "json_syntax" ->
                "تم فك تعبير حمولة البحث، لكن النص الناتج ليس JSON صالحًا؛ راجع حقول فك النص والتعبير في سياق الخطأ."
            error.type == "YOUTUBE_SEARCH_FAILED" &&
                error.context["payload_parser_failure_stage"] == "root_shape" ->
                "تم تحليل JSON لكن بنية الجذر لا تطابق شكل بيانات بحث YouTube المتوقع."
            error.type == "AUDIO_EXTRACTION_FAILED" ->
                "تم تنزيل المصدر بنجاح، ثم فشل استخراج المسار الصوتي من الملف المحلي؛ أخطاء الشبكة السابقة لا تُعامل كسبب جذري للحادثة الأخيرة."
            rootCause == "STORAGE_ERROR" ->
                "اكتمل تجهيز الملف، لكن تعذر حفظه في موقع التخزين النهائي للمستخدم."
            rootCause.startsWith("HTTP_") ->
                "تعذر إكمال العملية بسبب رفض أو خطأ HTTP أثناء الوصول إلى المصدر."
            isNetworkFailureCause(rootCause) ->
                "تعذر جلب صفحة المصدر بسبب خطأ شبكي مصنف: $rootCause. أعد المحاولة بعد التحقق من الاتصال أو إعدادات الشبكة."
            else ->
                "حدث خطأ في مرحلة ${stageOf(error)}؛ التقرير يضم تسلسل الحادثة وسياقها الكامل قبل الفشل."
        }

    private fun eventSequence(log: DiagnosticLog): Long =
        log.context["event_sequence"]?.toLongOrNull() ?: 0L

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(epochMs))
}
