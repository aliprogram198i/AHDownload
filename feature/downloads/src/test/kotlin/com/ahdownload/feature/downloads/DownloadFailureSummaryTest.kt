package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureSummaryTest {
    @Test
    fun knownWorkerFailureCodesHaveSpecificActionableMessages() {
        val codes = mapOf(
            "storage_space_low" to "مساحة التخزين",
            "youtube_refresh_failed" to "YouTube",
            "manifest_download_failed" to "مصدر البث",
            "mux_failed" to "دمج",
            "audio_extraction_error" to "استخراج الصوت",
            "destination_storage_error" to "حفظ الملف",
            "network_error" to "الشبكة",
            "invalid_response" to "بيانات غير صالحة",
        )

        codes.forEach { (code, expectedPhrase) ->
            val summary = failureSummary(record(code))
            assertTrue("Missing expected explanation for $code: $summary", summary.contains(expectedPhrase, ignoreCase = true))
            assertNotEquals("تعذر إكمال التنزيل. أعد المحاولة، وإذا تكرر الخطأ افتح تفاصيل التشخيص.", summary)
        }
    }

    @Test
    fun httpErrorsAreExplainedWithoutEchoingRawDetails() {
        assertTrue(failureSummary(record("http_error", "403")).contains("403"))
        assertTrue(failureSummary(record("http_error", "404")).contains("المصدر لم يعد متاحًا"))
        assertTrue(failureSummary(record("http_error", "429")).contains("الانتظار"))
        assertFalse(failureSummary(record("http_error", "403")).contains("https://"))
    }

    @Test
    fun unknownFailureCodeFallsBackToSafeGuidance() {
        val summary = failureSummary(record("unknown_private_detail"))
        assertTrue(summary.contains("تفاصيل التشخيص"))
        assertFalse(summary.contains("unknown_private_detail"))
    }

    private fun record(code: String, detail: String? = null) = DownloadRecord(
        task = DownloadTask(
            id = "failure-summary-test",
            sourceUrl = "https://example.com/media.mp4",
            destinationPath = "/tmp/media.mp4",
        ),
        status = DownloadStatus.FAILED,
        bytesDownloaded = 0L,
        totalBytes = null,
        failureCode = code,
        failureDetail = detail,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
