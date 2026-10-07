package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.domain.download.DownloadProcessingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRecordJsonCodecTest {
    private val codec = DownloadRecordJsonCodec()

    @Test
    fun roundTripPreservesDownloadRecord() {
        val original = DownloadRecord(
            task = DownloadTask(
                id = "task-1",
                sourceUrl = "https://example.com/video.mp4",
                destinationPath = "/downloads/video.mp4",
                processingMode = DownloadProcessingMode.ExtractAudio,
            ),
            status = DownloadStatus.DOWNLOADING,
            bytesDownloaded = 1024,
            totalBytes = 4096,
            failureCode = null,
            failureDetail = null,
            destinationUri = "content://media/external/video/media/42",
            createdAtEpochMs = 1000,
            updatedAtEpochMs = 2000,
        )

        val restored = codec.decode(codec.encode(listOf(original)))

        assertEquals(listOf(original), restored)
        assertNull(restored.single().failureCode)
        assertEquals("content://media/external/video/media/42", restored.single().destinationUri)
    }

    @Test
    fun legacyRecordWithoutProcessingModeDefaultsToDirect() {
        val json = """
            [{"task":{"id":"task-legacy","sourceUrl":"https://example.com/video.mp4","destinationPath":"/downloads/video.mp4"},
              "status":"COMPLETED","bytesDownloaded":1,"totalBytes":1,"failureCode":null,"failureDetail":null,
              "destinationUri":null,"createdAtEpochMs":1,"updatedAtEpochMs":1}]
        """.trimIndent()

        val restored = codec.decode(json)

        assertEquals(DownloadProcessingMode.Direct, restored.single().task.processingMode)
    }

    @Test
    fun decodeEmptyArrayReturnsEmptyList() {
        assertEquals(emptyList<DownloadRecord>(), codec.decode("[]"))
    }
}
