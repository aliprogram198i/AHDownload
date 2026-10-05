package com.ahdownload.domain.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

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

        assertEquals(
            DownloadFailure.InvalidResponse,
            assertIs<DownloadState.Failed>(states.last()).reason,
        )
        assertEquals(false, sink.committed)
        assertEquals(false, sink.tempOpened)
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

        assertEquals(
            DownloadFailure.HttpError(403),
            assertIs<DownloadState.Failed>(states.last()).reason,
        )
    }

    @Test
    fun removesPartialDataOnNetworkFailure() = runTest {
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
        assertEquals(false, sink.committed)
        assertEquals(1, sink.discardCount)
    }

    private fun task() = DownloadTask("task-1", "https://cdn.example/video.mp4", "/tmp/video.mp4")

    private class FakeSource(private val response: DownloadResponse) : DownloadByteStream {
        override suspend fun open(url: String): DownloadResponse = response
    }

    private class FakeSink : AtomicFileSink {
        private var data = ByteArrayOutputStream()
        var committed = false
        var tempOpened = false
        var discardCount = 0

        override suspend fun openTemporary(destinationPath: String): java.io.OutputStream {
            tempOpened = true
            data = ByteArrayOutputStream()
            return data
        }

        override suspend fun commit(destinationPath: String) {
            committed = true
        }

        override suspend fun discard(destinationPath: String) {
            discardCount++
            data.reset()
        }

        fun committedData(): String = data.toString(Charsets.UTF_8)
    }

    private class FailingInputStream : java.io.InputStream() {
        override fun read(): Int = throw IOException("network")
    }
}
