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
        assertEquals(listOf(null), ranges)
        assertEquals("GET", result.method)
        assertEquals(null, result.range)
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
    fun retriesYouTube403WithoutSessionHeaders() = runTest {
        val requestHeaders = mutableListOf<Map<String, String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requestHeaders += chain.request().headers.names().associateWith { name ->
                    chain.request().header(name).orEmpty()
                }
                val hasCookie = chain.request().header("Cookie") != null
                val response = if (hasCookie) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(byteArrayOf().toResponseBody(null))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
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
        assertEquals(2, requestHeaders.size)
        assertTrue(requestHeaders.first().keys.any { it.equals("Cookie", ignoreCase = true) })
        assertTrue(requestHeaders[1].keys.none { it.equals("Cookie", ignoreCase = true) })
        assertTrue(requestHeaders[1].keys.none { it.equals("Origin", ignoreCase = true) })
        assertTrue(requestHeaders[1].keys.none { it.equals("Referer", ignoreCase = true) })
    }

}
