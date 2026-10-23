package com.ahdownload.domain.resolver

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Small HTTP text client for resolvers.
 *
 * Requests use OkHttp's asynchronous call API so coroutine cancellation reaches the underlying
 * network call immediately. This matters for superseded search requests and navigation changes:
 * cancelling the caller must not leave a blocking HTTP request running until its timeout.
 */
class OkHttpTextClient(
    private val client: Call.Factory = OkHttpClient.Builder()
        .callTimeout(DEFAULT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(),
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) : HttpTextClient {
    override suspend fun get(url: String): String = get(url, emptyMap(), emptyMap())

    override suspend fun get(url: String, headers: Map<String, String>): String =
        get(url, headers, emptyMap())

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        diagnosticContext: Map<String, String>,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        return execute(request, diagnosticContext)
    }

    override suspend fun postJson(url: String, body: String): String =
        postJson(url, body, emptyMap(), emptyMap())

    override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
        postJson(url, body, headers, emptyMap())

    override suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        diagnosticContext: Map<String, String>,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request, diagnosticContext)
    }

    private suspend fun execute(
        request: Request,
        diagnosticContext: Map<String, String>,
    ): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        if (!continuation.isActive) {
            call.cancel()
            return@suspendCancellableCoroutine
        }

        val operation = diagnosticContext["diagnostic_operation"]
            ?: if (diagnosticContext["platform"] == "YouTube") "youtube.resolve" else "http.request"
        val safeContext = diagnosticContext.filterKeys { it != "diagnostic_operation" }

        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    // Cancellation is control flow, not a new transport incident.
                    if (continuation.isCancelled) return
                    logger.log(
                        level = DiagnosticLevel.WARNING,
                        type = "HTTP_REQUEST",
                        reason = "http_transport_failed",
                        operation = operation,
                        context = requestEventContext(
                            request = request,
                            diagnosticContext = safeContext,
                            outcome = "transport_error",
                        ),
                        throwable = error,
                    )
                    continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val successful = it.isSuccessful
                            logger.log(
                                level = if (successful) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                                type = "HTTP_REQUEST",
                                reason = if (successful) "http_response_received" else "http_response_error",
                                operation = operation,
                                context = requestEventContext(
                                    request = request,
                                    diagnosticContext = safeContext,
                                    outcome = if (successful) "success" else "http_error",
                                    statusCode = it.code,
                                ),
                                throwable = null,
                            )
                            if (!successful) throw IOException("HTTP " + it.code)
                            it.body.string()
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        } catch (error: Throwable) {
            call.cancel()
            continuation.resumeWith(Result.failure(error))
        }
    }

    private fun requestEventContext(
        request: Request,
        diagnosticContext: Map<String, String>,
        outcome: String,
        statusCode: Int? = null,
    ): Map<String, String> = buildMap {
        putAll(diagnosticContext)
        put("stage", diagnosticContext["stage"] ?: "RESOLUTION")
        put("request_method", request.method)
        put("request_host", request.url.host.removePrefix("www."))
        put("request_path", request.url.encodedPath)
        put("request_outcome", outcome)
        statusCode?.let { put("http_status", it.toString()) }
    }

    private companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 20L * 1000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                " (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
