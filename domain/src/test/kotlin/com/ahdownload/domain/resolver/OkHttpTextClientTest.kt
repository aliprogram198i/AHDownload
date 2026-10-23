package com.ahdownload.domain.resolver

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun cancellingCoroutineCancelsUnderlyingHttpCall() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val callReference = AtomicReference<PendingCall?>()
        val factory = Call.Factory { request ->
            PendingCall(request, requestStarted).also(callReference::set)
        }
        val textClient = OkHttpTextClient(client = factory)

        val requestJob = launch {
            textClient.get("https://example.com/media-page")
        }

        assertTrue(
            "The fake HTTP call should have been enqueued.",
            withContext(Dispatchers.IO) { requestStarted.await(2, TimeUnit.SECONDS) },
        )
        requestJob.cancelAndJoin()

        assertTrue(
            "Cancelling the coroutine must cancel the underlying OkHttp call.",
            callReference.get()?.wasCancelled == true,
        )
    }

    private class PendingCall(
        private val originalRequest: Request,
        private val requestStarted: CountDownLatch,
    ) : Call {
        @Volatile
        private var executed = false

        @Volatile
        var wasCancelled = false
            private set

        override fun request(): Request = originalRequest

        override fun execute(): Response = throw UnsupportedOperationException("Test call is asynchronous only")

        override fun enqueue(responseCallback: Callback) {
            executed = true
            requestStarted.countDown()
        }

        override fun cancel() {
            wasCancelled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = wasCancelled

        override fun timeout(): Timeout = Timeout()

        override fun clone(): Call = PendingCall(originalRequest, CountDownLatch(0))
    }
}
