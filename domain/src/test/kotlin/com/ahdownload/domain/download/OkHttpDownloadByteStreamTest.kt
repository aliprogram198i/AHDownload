package com.ahdownload.domain.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

class OkHttpDownloadByteStreamTest {

    @Test
    fun freshBrowserAlignedRequestDropsCapturedRangeOnAll403Retries() = runBlocking {
        val requests = mutableListOf<Request>()
        val response = OkHttpDownloadByteStream(client = retryTestClient(requests)).open(
            url = YOUTUBE_MEDIA_URL,
            rangeStart = 0L,
            headers = mapOf(
                "X-YouTube-Client-Name" to "56",
                "Range" to "bytes=8192-",
            ),
        )

        try {
            assertEquals(200, response.statusCode)
            assertEquals(listOf(null, null, null), requests.map { it.header("Range") })
            assertEquals(listOf("56", "56", "56"), requests.map { it.header("X-YouTube-Client-Name") })
        } finally {
            response.body.close()
        }
    }

    @Test
    fun resumeRangeComesOnlyFromDownloadOffsetAndIsStableAcrossRetries() = runBlocking {
        val requests = mutableListOf<Request>()
        val response = OkHttpDownloadByteStream(client = retryTestClient(requests)).open(
            url = YOUTUBE_MEDIA_URL,
            rangeStart = 1024L,
            headers = mapOf(
                "X-YouTube-Client-Name" to "56",
                // Mixed casing protects against a case-sensitive filter regression.
                "rAnGe" to "bytes=8192-",
            ),
        )

        try {
            assertEquals(200, response.statusCode)
            assertEquals(
                listOf("bytes=1024-", "bytes=1024-", "bytes=1024-"),
                requests.map { it.header("Range") },
            )
        } finally {
            response.body.close()
        }
    }

    @Test
    fun nonBrowserFallbackDoesNotReuseCapturedRange() = runBlocking {
        val requests = mutableListOf<Request>()
        val response = OkHttpDownloadByteStream(client = retryTestClient(requests)).open(
            url = YOUTUBE_MEDIA_URL,
            rangeStart = 0L,
            headers = mapOf("Range" to "bytes=8192-"),
        )

        try {
            assertEquals(200, response.statusCode)
            assertEquals(
                listOf("bytes=0-", "bytes=0-", null),
                requests.map { it.header("Range") },
            )
        } finally {
            response.body.close()
        }
    }

    private fun retryTestClient(requests: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                val statusCode = if (requests.size < 3) 403 else 200
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 403) "Forbidden" else "OK")
                    .body("test".toResponseBody())
                    .build()
            }
            .build()

    private companion object {
        const val YOUTUBE_MEDIA_URL =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4"
    }
}
