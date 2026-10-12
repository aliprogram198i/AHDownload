package com.ahdownload.domain.validation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class OkHttpMediaProbeTest {

    @Test
    fun usesDownloadAlignedGetForYouTubeInsteadOfHeadOrRangeProbe() = runTest {
        val methods = mutableListOf<String>()
        val ranges = mutableListOf<String?>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                methods += chain.request().method
                ranges += chain.request().header("Range")
                assertTrue(chain.request().header("Referer") == "https://www.youtube.com/")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/webm; codecs=\"vp9\"")
                    .header("Content-Length", "123456")
                    .body(
                        byteArrayOf(0)
                            .toString(Charsets.ISO_8859_1)
                            .toResponseBody("video/webm".toMediaType()),
                    )
                    .build()
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://example.googlevideo.com/videoplayback",
            mapOf("Cookie" to "SID=redacted"),
            operationId = "op-youtube-probe",
        )

        assertEquals(200, result.statusCode)
        assertEquals("video/webm; codecs=\"vp9\"", result.contentType)
        assertEquals(123456L, result.contentLengthBytes)
        assertEquals(listOf("GET"), methods)
        assertTrue(ranges.size == 1 && ranges.single() == null)
        assertEquals("GET", result.method)
        assertEquals<String?>(null, result.range)
    }

    @Test
    fun returnsYouTubeUmpProbeResultWithoutTreatingItAsVideoOrRetryingItHere() = runTest {
        val requestCount = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requestCount += request.method
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.yt-ump")
                    .body(byteArrayOf(0).toResponseBody("application/vnd.yt-ump".toMediaType()))
                    .build()
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://rr2.googlevideo.com/videoplayback",
            headers = emptyMap(),
            operationId = "op-youtube-ump",
        )

        assertEquals(200, result.statusCode)
        assertEquals("application/vnd.yt-ump", result.contentType)
        assertEquals(listOf("GET"), requestCount)
    }

    @Test
    fun keepsHeadThenRangeFallbackForGenericMedia() = runTest {
        val methods = mutableListOf<String>()
        val ranges = mutableListOf<String?>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                methods += chain.request().method
                ranges += chain.request().header("Range")

                if (chain.request().method == "HEAD") {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Range", "bytes 0-0/123456")
                        .body(
                            byteArrayOf(0)
                                .toString(Charsets.ISO_8859_1)
                                .toResponseBody("video/mp4".toMediaType()),
                        )
                        .build()
                }
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://cdn.example.com/video.mp4",
            emptyMap(),
            operationId = "op-generic-probe",
        )

        assertEquals(206, result.statusCode)
        assertEquals("video/mp4", result.contentType)
        assertEquals(123456L, result.contentLengthBytes)
        assertEquals(listOf("HEAD", "GET"), methods)
        assertEquals(listOf(null, "bytes=0-0"), ranges)
    }

    @Test
    fun probesOneByteRangeWhenHeadHasNoTotalSize() = runTest {
        val methods = mutableListOf<String>()
        val ranges = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                methods += request.method
                ranges += request.header("Range")
                if (request.method == "HEAD") {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .body(object : okhttp3.ResponseBody() {
                            private val bytes = okio.Buffer().writeByte(0)
                            override fun contentType() = "video/mp4".toMediaType()
                            override fun contentLength() = -1L
                            override fun source() = bytes
                        })
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Range", "bytes 0-0/123456")
                        .body(byteArrayOf(0).toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://cdn.example.com/unknown-length.mp4",
            emptyMap(),
            operationId = "op-head-unknown-size",
        )

        assertEquals(206, result.statusCode)
        assertEquals(123456L, result.contentLengthBytes)
        assertEquals(listOf("HEAD", "GET"), methods)
        assertEquals(listOf(null, "bytes=0-0"), ranges)
    }

    @Test
    fun doesNotTreatPartialBodyLengthAsWholeResourceSize() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                if (request.method == "HEAD") {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(405)
                        .message("Method Not Allowed")
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Length", "1")
                        .body(byteArrayOf(0).toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://cdn.example.com/range-without-total.mp4",
            emptyMap(),
            operationId = "op-no-content-range-total",
        )

        assertEquals(206, result.statusCode)
        assertEquals<Long?>(null, result.contentLengthBytes)
    }

    @Test
    fun retriesYouTube403WithoutSessionHeadersForGenericSessionContext() = runTest {
        val requestHeaders = mutableListOf<Map<String, String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val current = chain.request()
                requestHeaders += current.headers.names().associateWith { name ->
                    current.header(name).orEmpty()
                }
                val hasCookie = current.header("Cookie") != null
                val response = if (hasCookie) {
                    Response.Builder()
                        .request(current)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(byteArrayOf().toResponseBody(null))
                        .build()
                } else {
                    Response.Builder()
                        .request(current)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "audio/mp4")
                        .header("Content-Length", "123")
                        .body(
                            byteArrayOf(0)
                                .toString(Charsets.ISO_8859_1)
                                .toResponseBody("audio/mp4".toMediaType()),
                        )
                        .build()
                }
                response
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://example.googlevideo.com/videoplayback?mime=audio%2Fmp4",
            mapOf(
                "Cookie" to "SID=redacted",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
            ),
            operationId = "op-youtube-403-retry",
        )

        assertEquals(200, result.statusCode)
        assertEquals(3, requestHeaders.size)
        assertTrue(requestHeaders[0].keys.any { it.equals("Cookie", ignoreCase = true) })
        assertTrue(requestHeaders[1].keys.any { it.equals("Cookie", ignoreCase = true) })
        assertTrue(requestHeaders[1].keys.any { it.equals("Referer", ignoreCase = true) })
        assertTrue(requestHeaders[1].keys.any { it.equals("Origin", ignoreCase = true) })
        assertTrue(requestHeaders[2].keys.none { it.equals("Cookie", ignoreCase = true) })
        assertTrue(requestHeaders[2].keys.none { it.equals("Referer", ignoreCase = true) })
        assertTrue(requestHeaders[2].keys.none { it.equals("Origin", ignoreCase = true) })
    }

    @Test
    fun preservesBrowserClientContextAndCapturedRangeForYouTubeValidation() = runTest {
        val requestHeaders = mutableListOf<Map<String, String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val current = chain.request()
                requestHeaders += current.headers.names().associateWith { name ->
                    current.header(name).orEmpty()
                }

                val response = if (requestHeaders.size == 1) {
                    Response.Builder()
                        .request(current)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(byteArrayOf().toResponseBody(null))
                        .build()
                } else {
                    assertEquals("mweb", current.header("X-YouTube-Client-Name"))
                    assertEquals("2.20260708.00.00", current.header("X-YouTube-Client-Version"))
                    assertEquals("visitor", current.header("X-Goog-Visitor-Id"))
                    assertEquals(null, current.header("Range"))
                    assertEquals("https://www.youtube.com/watch?v=test", current.header("Referer"))
                    Response.Builder()
                        .request(current)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "audio/mp4")
                        .header("Content-Length", "123")
                        .body(
                            byteArrayOf(0)
                                .toString(Charsets.ISO_8859_1)
                                .toResponseBody("audio/mp4".toMediaType()),
                        )
                        .build()
                }
                response
            })
            .build()

        val result = OkHttpMediaProbe(client).probe(
            "https://example.googlevideo.com/videoplayback?mime=audio%2Fmp4",
            mapOf(
                "Cookie" to "SID=redacted",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/watch?v=test",
                "X-Goog-Visitor-Id" to "visitor",
                "X-YouTube-Client-Name" to "mweb",
                "X-YouTube-Client-Version" to "2.20260708.00.00",
                "Range" to "bytes=0-1048575",
            ),
            operationId = "op-browser-context",
        )

        assertEquals(200, result.statusCode)
        assertEquals(2, requestHeaders.size)
        assertEquals("bytes=0-1048575", requestHeaders.first()["Range"])
        assertEquals(null, requestHeaders[1]["Range"])
        assertEquals("mweb", requestHeaders[1]["X-YouTube-Client-Name"])
        assertEquals("visitor", requestHeaders[1]["X-Goog-Visitor-Id"])
        assertTrue(requestHeaders[1].keys.any { it.equals("Cookie", ignoreCase = true) })
    }

}
