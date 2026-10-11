package com.ahdownload.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the actual native FFmpeg runtime packaged in the APK, not Gradle metadata. */
@RunWith(AndroidJUnit4::class)
class FfmpegProtocolSupportTest {
    @Test
    fun ffmpegRuntimeIncludesHttpsInputSupportRequiredByHlsDash() {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-protocols"))
        assertTrue(
            "FFmpeg protocol listing must execute successfully: ${session.failStackTrace.orEmpty()}",
            ReturnCode.isSuccess(session.returnCode),
        )

        val output = session.output.orEmpty()
        val inputProtocols = output.substringAfter("Input:", missingDelimiterValue = "")
            .substringBefore("Output:", missingDelimiterValue = "")

        assertTrue(
            "The packaged FFmpeg runtime lacks HTTPS input support required by HLS/DASH URLs",
            Regex("""(?m)^\s*https\s*$""").containsMatchIn(inputProtocols),
        )
        assertTrue(
            "The packaged FFmpeg runtime lacks HLS demux/input support",
            Regex("""(?m)^\s*hls\s*$""").containsMatchIn(inputProtocols),
        )
    }
}
