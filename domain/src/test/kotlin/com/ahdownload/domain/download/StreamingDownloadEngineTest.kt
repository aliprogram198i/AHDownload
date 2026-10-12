package com.ahdownload.domain.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamingDownloadEngineTest {

    @Test
    fun streamsBytesAndReportsProgress() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 5,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("hello".encodeToByteArray()),
                ),
            ),
            sink = sink,
            bufferSize = 2,
        ).download(task(), states::add)
        assertIs<DownloadState.Queued>(states[0])
        assertIs<DownloadState.Preparing>(states[1])
        assertEquals(DownloadState.Downloading(0, 5), states[2])
        assertEquals(DownloadState.Downloading(2, 5), states[3])
        assertEquals(DownloadState.Downloading(4, 5), states[4])
        assertEquals(DownloadState.Downloading(5, 5), states[5])
        assertIs<DownloadState.Completed>(states.last())
        assertEquals("hello", sink.committedData())
    }

    @Test
    fun doesNotCommitWhenBodyEndsBeforeDeclaredContentLength() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 10,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("short".encodeToByteArray()),
                ),
            ),
            sink = sink,
        ).download(task(), states::add)
        assertEquals(
            DownloadFailure.IncompleteResponse(expectedBytes = 10, receivedBytes = 5),
            assertIs<DownloadState.Failed>(states.last()).reason,
        )
        assertFalse(sink.committed)
        assertEquals("short", sink.committedData())
    }

    @Test
    fun usesValidatedExpectedLengthWhenResponseOmitsContentLength() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = null,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("short".encodeToByteArray()),
                ),
            ),
            sink = sink,
        ).download(task().copy(expectedBytes = 10), states::add)
        assertEquals(
            DownloadFailure.IncompleteResponse(expectedBytes = 10, receivedBytes = 5),
            assertIs<DownloadState.Failed>(states.last()).reason,
        )
        assertFalse(sink.committed)
    }

    @Test
    fun rejectsResponseLengthThatConflictsWithValidatedCandidateLength() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 6,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("abcdef".encodeToByteArray()),
                ),
            ),
            sink = sink,
        ).download(task().copy(expectedBytes = 5), states::add)

        assertEquals(DownloadFailure.InvalidResponse, assertIs<DownloadState.Failed>(states.last()).reason)
        assertFalse(sink.tempOpened)
        assertFalse(sink.committed)
    }

    @Test
    fun forwardsTaskRequestHeadersToSource() = runTest {
        val source = CapturingSource(
            DownloadResponse(
                statusCode = 200,
                contentLengthBytes = 1,
                contentType = "video/mp4",
                body = ByteArrayInputStream(byteArrayOf(1)),
            ),
        )
        StreamingDownloadEngine(source = source, sink = FakeSink()).download(
            task = DownloadTask(
                "task-headers",
                "https://cdn.example/video.mp4",
                "/tmp/video.mp4",
                requestHeaders = mapOf(
                    "User-Agent" to "WebView-UA",
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                ),
            ),
            onState = {},
        )
        assertEquals("WebView-UA", source.headers["User-Agent"])
        assertEquals("https://www.youtube.com/", source.headers["Referer"])
        assertEquals("https://www.youtube.com", source.headers["Origin"])
    }

    @Test
    fun rejectsHtmlWithoutCreatingFinalFile() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 10,
                    contentType = "text/html",
                    body = ByteArrayInputStream("page".encodeToByteArray()),
                ),
            ),
            sink,
        ).download(task(), states::add)
        assertEquals(DownloadFailure.InvalidResponse, assertIs<DownloadState.Failed>(states.last()).reason)
        assertFalse(sink.committed)
        assertFalse(sink.tempOpened)
    }

    @Test
    fun reportsHttpFailure() = runTest {
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            FakeSource(
                DownloadResponse(
                    statusCode = 403,
                    contentLengthBytes = null,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream(ByteArray(0)),
                ),
            ),
            FakeSink(),
        ).download(task(), states::add)
        assertEquals(DownloadFailure.HttpError(403), assertIs<DownloadState.Failed>(states.last()).reason)
    }

    @Test
    fun removesPartialDataOnNetworkFailureWhenWriteFails() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 5,
                    contentType = "video/mp4",
                    body = FailingInputStream(),
                ),
            ),
            sink = sink,
        ).download(task(), states::add)
        assertEquals(DownloadFailure.NetworkError, assertIs<DownloadState.Failed>(states.last()).reason)
        assertFalse(sink.committed)
        assertEquals(0, sink.discardCount)
    }

    @Test
    fun resumesExistingPartialFileOnlyWhenContentRangeMatchesOffset() = runTest {
        val sink = FakeSink()
        sink.seed("he".encodeToByteArray())
        val states = mutableListOf<DownloadState>()
        val source = FakeSource(
            DownloadResponse(
                statusCode = 206,
                contentLengthBytes = 3,
                contentType = "video/mp4",
                body = ByteArrayInputStream("llo".encodeToByteArray()),
                totalBytes = 5,
                rangeStartBytes = 2,
                rangeEndBytes = 4,
            ),
        )
        StreamingDownloadEngine(source = source, sink = sink, bufferSize = 2)
            .download(task(), states::add)

        assertEquals(DownloadState.Downloading(2, 5), states[2])
        assertIs<DownloadState.Completed>(states.last())
        assertEquals("hello", sink.committedData())
        assertEquals(2L, source.requestedRange)
    }

    @Test
    fun restartsFromZeroWhenServerIgnoresRangeAndReturnsFullBody() = runTest {
        val sink = FakeSink()
        sink.seed("old!".encodeToByteArray())
        val states = mutableListOf<DownloadState>()
        val source = FakeSource(
            DownloadResponse(
                statusCode = 200,
                contentLengthBytes = 5,
                contentType = "video/mp4",
                body = ByteArrayInputStream("hello".encodeToByteArray()),
            ),
        )

        StreamingDownloadEngine(source = source, sink = sink).download(task(), states::add)

        assertIs<DownloadState.Completed>(states.last())
        assertEquals("hello", sink.committedData())
        assertEquals(1, sink.discardCount)
        assertEquals(4L, source.requestedRange)
    }

    @Test
    fun discardsStalePartialAndRetriesOnceWhenContentRangeStartsAtWrongOffset() = runTest {
        val sink = FakeSink()
        sink.seed("stale".encodeToByteArray())
        val states = mutableListOf<DownloadState>()
        val source = SequenceSource(
            listOf(
                DownloadResponse(
                    statusCode = 206,
                    contentLengthBytes = 3,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("bad".encodeToByteArray()),
                    totalBytes = 6,
                    rangeStartBytes = 0,
                    rangeEndBytes = 2,
                ),
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 5,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("fresh".encodeToByteArray()),
                ),
            ),
        )

        StreamingDownloadEngine(source = source, sink = sink).download(task(), states::add)

        assertEquals(listOf(5L, 0L), source.requestedRanges)
        assertEquals("fresh", sink.committedData())
        assertEquals(1, sink.discardCount)
        assertIs<DownloadState.Completed>(states.last())
    }

    @Test
    fun clearsUnsatisfiablePartialRangeAndRestartsFromZero() = runTest {
        val sink = FakeSink()
        sink.seed("stale!".encodeToByteArray())
        val states = mutableListOf<DownloadState>()
        val source = SequenceSource(
            listOf(
                DownloadResponse(
                    statusCode = 416,
                    contentLengthBytes = 0,
                    contentType = "text/plain",
                    body = ByteArrayInputStream(ByteArray(0)),
                ),
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 5,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("fresh".encodeToByteArray()),
                ),
            ),
        )

        StreamingDownloadEngine(source = source, sink = sink).download(task(), states::add)

        assertEquals(listOf(6L, 0L), source.requestedRanges)
        assertEquals("fresh", sink.committedData())
        assertEquals(1, sink.discardCount)
        assertIs<DownloadState.Completed>(states.last())
    }

    @Test
    fun resumesBoundedPartialResponsesWithoutAppendingDuplicateBytes() = runTest {
        val sink = FakeSink()
        val firstStates = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = SequenceSource(
                listOf(
                    DownloadResponse(
                        statusCode = 206,
                        contentLengthBytes = 3,
                        contentType = "video/mp4",
                        body = ByteArrayInputStream("hel".encodeToByteArray()),
                        totalBytes = 5,
                        rangeStartBytes = 0,
                        rangeEndBytes = 2,
                    ),
                ),
            ),
            sink = sink,
        ).download(task().copy(expectedBytes = 5), firstStates::add)

        assertEquals(
            DownloadFailure.IncompleteResponse(expectedBytes = 5, receivedBytes = 3),
            assertIs<DownloadState.Failed>(firstStates.last()).reason,
        )
        assertFalse(sink.committed)
        assertEquals("hel", sink.committedData())

        val secondStates = mutableListOf<DownloadState>()
        val secondSource = SequenceSource(
            listOf(
                DownloadResponse(
                    statusCode = 206,
                    contentLengthBytes = 2,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("lo".encodeToByteArray()),
                    totalBytes = 5,
                    rangeStartBytes = 3,
                    rangeEndBytes = 4,
                ),
            ),
        )
        StreamingDownloadEngine(source = secondSource, sink = sink)
            .download(task().copy(expectedBytes = 5), secondStates::add)

        assertEquals(listOf(3L), secondSource.requestedRanges)
        assertEquals("hello", sink.committedData())
        assertIs<DownloadState.Completed>(secondStates.last())
    }

    @Test
    fun rejectsPartialResponseWithUnknownTotalWhenNoValidatedSizeExists() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 206,
                    contentLengthBytes = 3,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("hel".encodeToByteArray()),
                    rangeStartBytes = 0,
                    rangeEndBytes = 2,
                ),
            ),
            sink = sink,
        ).download(task(), states::add)

        assertEquals(DownloadFailure.InvalidResponse, assertIs<DownloadState.Failed>(states.last()).reason)
        assertFalse(sink.tempOpened)
        assertFalse(sink.committed)
    }

    @Test
    fun rejects206WithoutValidContentRange() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 206,
                    contentLengthBytes = 3,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream("abc".encodeToByteArray()),
                    totalBytes = 5,
                ),
            ),
            sink = sink,
        ).download(task(), states::add)

        assertEquals(DownloadFailure.InvalidResponse, assertIs<DownloadState.Failed>(states.last()).reason)
        assertFalse(sink.committed)
        assertFalse(sink.tempOpened)
    }

    @Test
    fun rejectsEmptyResponseWithoutPublishingAFile() = runTest {
        val sink = FakeSink()
        val states = mutableListOf<DownloadState>()
        StreamingDownloadEngine(
            source = FakeSource(
                DownloadResponse(
                    statusCode = 200,
                    contentLengthBytes = 0,
                    contentType = "video/mp4",
                    body = ByteArrayInputStream(ByteArray(0)),
                ),
            ),
            sink = sink,
        ).download(task(), states::add)

        assertEquals(DownloadFailure.InvalidResponse, assertIs<DownloadState.Failed>(states.last()).reason)
        assertFalse(sink.committed)
        assertTrue(sink.discardCount >= 1)
    }

    private fun task() = DownloadTask("task-1", "https://cdn.example/video.mp4", "/tmp/video.mp4")

    private class CapturingSource(private val response: DownloadResponse) : DownloadByteStream {
        var headers: Map<String, String> = emptyMap()
        override suspend fun open(url: String, rangeStart: Long, headers: Map<String, String>): DownloadResponse {
            this.headers = headers
            return response
        }
    }

    private class FakeSource(private val response: DownloadResponse) : DownloadByteStream {
        var requestedRange = 0L
        override suspend fun open(
            url: String,
            rangeStart: Long,
            headers: Map<String, String>,
        ): DownloadResponse {
            requestedRange = rangeStart
            return response
        }
    }

    private class SequenceSource(private val responses: List<DownloadResponse>) : DownloadByteStream {
        var requestedRanges = mutableListOf<Long>()
        private var nextIndex = 0
        override suspend fun open(
            url: String,
            rangeStart: Long,
            headers: Map<String, String>,
        ): DownloadResponse {
            requestedRanges += rangeStart
            val result = responses[nextIndex.coerceAtMost(responses.lastIndex)]
            nextIndex++
            return result
        }
    }

    private class FakeSink : AtomicFileSink {
        private var data = ByteArrayOutputStream()
        var committed = false
        var tempOpened = false
        var discardCount = 0

        override suspend fun openTemporary(
            destinationPath: String,
            append: Boolean,
            transferId: String?,
        ): java.io.OutputStream {
            tempOpened = true
            if (!append) data = ByteArrayOutputStream()
            return data
        }

        fun seed(bytes: ByteArray) { data.write(bytes) }

        override suspend fun temporarySize(destinationPath: String, transferId: String?): Long = data.size().toLong()

        override suspend fun commit(destinationPath: String, transferId: String?) {
            committed = true
        }

        override suspend fun discard(destinationPath: String, transferId: String?) {
            discardCount++
            data.reset()
        }

        fun committedData(): String = data.toString(Charsets.UTF_8)
    }

    private class FailingInputStream : java.io.InputStream() {
        override fun read(): Int = throw IOException("network")
    }
}
