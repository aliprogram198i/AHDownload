package com.ahdownload.app.download

import com.ahdownload.domain.download.DownloadFailure
import com.ahdownload.domain.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNotificationPresentationTest {
    @Test
    fun keepsLocalizedLabelsForAllStableStates() {
        assertEquals("في قائمة الانتظار", DownloadState.Queued.toNotificationText(false, 0L))
        assertEquals("جاري تجهيز التنزيل", DownloadState.Preparing.toNotificationText(false, 0L))
        assertEquals("المساحة منخفضة — راقب التنزيل", DownloadState.Preparing.toNotificationText(true, 0L))
        assertEquals("تم الإيقاف المؤقت", DownloadState.Paused.toNotificationText(false, 0L))
        assertEquals("اكتمل التنزيل", DownloadState.Completed.toNotificationText(false, 0L))
        assertEquals("فشل التنزيل", DownloadState.Failed(DownloadFailure.NetworkError).toNotificationText(false, 0L))
        assertEquals("تم إلغاء التنزيل", DownloadState.Cancelled.toNotificationText(false, 0L))
    }

    @Test
    fun formatsProgressAndOptionalSpeedWithoutChangingTheStatusContract() {
        assertEquals(
            "جاري التنزيل — 50%",
            DownloadState.Downloading(bytesDownloaded = 512L, totalBytes = 1024L).toNotificationText(false, 0L),
        )
        assertEquals(
            "جاري التنزيل — 50% · مساحة منخفضة · 1 KB/s · 1ث",
            DownloadState.Downloading(bytesDownloaded = 1024L, totalBytes = 2048L)
                .toNotificationText(true, 1024L),
        )
        assertEquals(
            "جاري التنزيل",
            DownloadState.Downloading(bytesDownloaded = 512L, totalBytes = null).toNotificationText(false, 0L),
        )
    }

    @Test
    fun exposesTransferByteCountsOnlyForDownloadingState() {
        val downloading = DownloadState.Downloading(bytesDownloaded = 20L, totalBytes = 80L)

        assertEquals(80L, downloading.totalBytesOrNull())
        assertEquals(20L, downloading.bytesDownloadedOrNull())
        assertNull(DownloadState.Completed.totalBytesOrNull())
        assertNull(DownloadState.Completed.bytesDownloadedOrNull())
    }
}
