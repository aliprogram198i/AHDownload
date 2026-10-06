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
    fun retriesWithRangeGetWhenHeadIsForbidden() = runTest {
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

        val result = OkHttpMediaProbe(client).probe("https://example.googlevideo.com/videoplayback")

        assertEquals(206, result.statusCode)
        assertEquals("video/mp4", result.contentType)
        assertEquals(123456L, result.contentLengthBytes)
        assertEquals(listOf("HEAD", "GET"), methods)
        assertEquals(listOf(null, "bytes=0-0"), ranges)
        assertTrue(result.finalUrl.contains("googlevideo.com"))
    }
}
