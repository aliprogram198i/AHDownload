package com.ahdownload.domain.resolver.web

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDownloadResolverTest {
    @Test
    fun resolvesPdfFromMetadataAndSafelyDecodesFilename() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebDownloadResolver(client = client(requests, listOf(
            response(200, mapOf(
                "Content-Type" to "application/pdf; charset=binary",
                "Content-Length" to "2048",
                "Content-Disposition" to "attachment; filename*=UTF-8''report%20final.pdf",
            )),
        )))

        val result = resolver.resolve(request("https://example.com/download?id=42"))

        assertTrue(result is ResolverResult.Success)
        val success = result as ResolverResult.Success
        assertEquals("report final.pdf", success.title)
        assertEquals(1, success.candidates.size)
        val candidate = success.candidates.single()
        assertEquals("pdf", candidate.format.fileExtension)
        assertEquals("application/pdf", candidate.format.mimeType)
        assertEquals(2048L, candidate.format.fileSizeBytes)
        assertTrue(candidate.format.webFile)
        assertEquals(MediaKind.Unknown, candidate.format.kind)
        assertEquals("HEAD", requests.single().method)
    }

    @Test
    fun followsRedirectsManuallyAndUsesTheFinalFileUrl() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebDownloadResolver(client = client(requests, listOf(
            response(302, mapOf("Location" to "https://cdn.example.com/assets/archive")),
            response(200, mapOf(
                "Content-Type" to "application/zip",
                "Content-Length" to "4096",
            )),
        )))

        val result = resolver.resolve(request("https://example.com/get-file"))

        assertTrue(result is ResolverResult.Success)
        val success = result as ResolverResult.Success
        assertEquals("https://cdn.example.com/assets/archive", success.candidates.single().sourceUrl)
        assertEquals("archive.zip", success.title)
        assertEquals("zip", success.candidates.single().format.fileExtension)
        assertEquals(2, requests.size)
    }

    @Test
    fun fallsBackToRangeGetWhenHeadIsUnsupportedAndUsesContentRangeTotal() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebDownloadResolver(client = client(requests, listOf(
            response(405, emptyMap()),
            response(206, mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Range" to "bytes 0-0/12345",
                "Content-Length" to "1",
                "Content-Disposition" to "attachment; filename=backup.zip",
            )),
        )))

        val result = resolver.resolve(request("https://example.com/download"))

        assertTrue(result is ResolverResult.Success)
        val candidate = (result as ResolverResult.Success).candidates.single()
        assertEquals("zip", candidate.format.fileExtension)
        assertEquals(12345L, candidate.format.fileSizeBytes)
        assertEquals("HEAD", requests[0].method)
        assertEquals("GET", requests[1].method)
        assertEquals("bytes=0-0", requests[1].header("Range"))
    }

    @Test
    fun rejectsHtmlPagesInsteadOfSavingThemAsFiles() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebDownloadResolver(client = client(requests, listOf(
            response(200, mapOf("Content-Type" to "text/html; charset=utf-8")),
        )))

        val result = resolver.resolve(request("https://example.com/a-page"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.NoCandidates, (result as ResolverResult.Failure).code)
        assertFalse(requests.isEmpty())
    }

    @Test
    fun rejectsLocalDestinationsBeforeOpeningARequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val resolver = WebDownloadResolver(client = client(requests, emptyList()))

        val result = resolver.resolve(request("http://127.0.0.1/file.pdf"))

        assertTrue(result is ResolverResult.Failure)
        assertEquals(FailureCode.InvalidRequest, (result as ResolverResult.Failure).code)
        assertTrue(requests.isEmpty())
    }

    private fun request(url: String) = ResolverRequest(
        link = MediaLink(
            originalUrl = url,
            normalizedUrl = url,
            platform = MediaPlatform.Web,
            kind = MediaKind.Unknown,
        ),
        operationId = "test-operation",
    )

    private fun client(requests: MutableList<Request>, replies: List<ResponseSpec>): OkHttpClient {
        val remaining = replies.toMutableList()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                val spec = remaining.removeAt(0)
                val builder = Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(spec.code)
                    .message("test")
                    .body(ByteArray(0).toResponseBody())
                spec.headers.forEach { (name, value) -> builder.header(name, value) }
                builder.build()
            }
            .build()
    }

    private fun response(code: Int, headers: Map<String, String>) = ResponseSpec(code, headers)
    private data class ResponseSpec(val code: Int, val headers: Map<String, String>)
}
