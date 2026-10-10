package com.ahdownload.domain.resolver

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OkHttpTextClientTest {
    private data class CapturedEvent(
        val level: DiagnosticLevel,
        val type: String,
        val reason: String,
        val operation: String,
        val context: Map<String, String>,
    )

    @Test
    fun logsHttpStatusAndCorrelatesRequestWithoutRecordingQuerySecrets() {
        val events = mutableListOf<CapturedEvent>()
        val logger = DiagnosticLogger { level, type, reason, operation, context, _ ->
            events += CapturedEvent(level, type, reason, operation, context)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("blocked".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val textClient = OkHttpTextClient(client = client, logger = logger)

        val failure = runCatching {
            runBlocking {
                textClient.get(
                    url = "https://www.youtube.com/watch?v=abc123&token=private-token-value",
                    headers = emptyMap(),
                    diagnosticContext = mapOf(
                        "platform" to "YouTube",
                        "stage" to "RESOLUTION",
                        "video_id" to "abc123",
                        "operation_id" to "op-test",
                    ),
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(DiagnosticLevel.WARNING, event.level)
        assertEquals("HTTP_REQUEST", event.type)
        assertEquals("http_response_error", event.reason)
        assertEquals("youtube.resolve", event.operation)
        assertEquals("403", event.context["http_status"])
        assertEquals("http_error", event.context["request_outcome"])
        assertEquals("GET", event.context["request_method"])
        assertEquals("youtube.com", event.context["request_host"])
        assertEquals("/watch", event.context["request_path"])
        assertEquals("op-test", event.context["operation_id"])
        assertEquals("RESOLUTION", event.context["stage"])
        assertFalse(event.context.values.joinToString(" ").contains("private-token-value"))
    }
}
