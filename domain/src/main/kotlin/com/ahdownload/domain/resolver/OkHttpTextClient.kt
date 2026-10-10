package com.ahdownload.domain.resolver

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OkHttpTextClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) : HttpTextClient {
    override suspend fun get(url: String): String = get(url, emptyMap(), emptyMap())

    override suspend fun get(url: String, headers: Map<String, String>): String =
        get(url, headers, emptyMap())

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        diagnosticContext: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        execute(request, diagnosticContext)
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
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request, diagnosticContext)
    }

    private fun execute(request: Request, diagnosticContext: Map<String, String>): String {
        val operation = diagnosticContext["diagnostic_operation"]
            ?: if (diagnosticContext["platform"] == "YouTube") "youtube.resolve" else "http.request"
        val safeContext = diagnosticContext.filterKeys { it != "diagnostic_operation" }

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
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
            throw error
        }

        return response.use {
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

    private fun requestEventContext(
        request: Request,
        diagnosticContext: Map<String, String>,
        outcome: String,
        statusCode: Int? = null,
    ): Map<String, String> = buildMap {
        putAll(diagnosticContext)
        put("stage", diagnosticContext["stage"] ?: "RESOLUTION")
        put("request_method", request.method)
        put("request_host", request.url.host)
        put("request_path", request.url.encodedPath)
        put("request_outcome", outcome)
        statusCode?.let { put("http_status", it.toString()) }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                " (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
