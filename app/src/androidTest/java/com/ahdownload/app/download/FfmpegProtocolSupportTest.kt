package com.ahdownload.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfmpegProtocolSupportTest {
    @Test
    fun ffmpegRuntimeIncludesHttpAndHttpsProtocolsForManifestDownloads() {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-protocols"))
        val output = session.allLogsAsString.lowercase()

        assertTrue(
            "FFmpeg protocol inspection did not complete successfully",
            ReturnCode.isSuccess(session.returnCode),
        )
        assertTrue(
            "The bundled FFmpeg runtime does not list HTTP input support",
            output.contains("http"),
        )
        assertTrue(
            "The bundled FFmpeg runtime lacks HTTPS input support required by HLS/DASH URLs",
            output.contains("https"),
        )
    }
}
