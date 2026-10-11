package com.ahdownload.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the native FFmpeg runtime packaged in the APK supports streaming inputs. */
@RunWith(AndroidJUnit4::class)
class FfmpegProtocolSupportTest {
    @Test
    fun ffmpegRuntimeIncludesHttpsInputAndHlsDemuxer() {
        val protocolSession = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-protocols"))
        assertTrue(
            "FFmpeg protocol listing must execute successfully: ${protocolSession.failStackTrace.orEmpty()}",
            ReturnCode.isSuccess(protocolSession.returnCode),
        )

        val protocolsOutput = protocolSession.output.orEmpty()
        val inputProtocols = protocolsOutput.substringAfter("Input:", missingDelimiterValue = "")
            .substringBefore("Output:", missingDelimiterValue = "")
        assertTrue(
            "The packaged FFmpeg runtime lacks HTTPS input support required by HLS/DASH URLs",
            Regex("""(?m)^\s*https\s*$""").containsMatchIn(inputProtocols),
        )

        val demuxerSession = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-demuxers"))
        assertTrue(
            "FFmpeg demuxer listing must execute successfully: ${demuxerSession.failStackTrace.orEmpty()}",
            ReturnCode.isSuccess(demuxerSession.returnCode),
        )
        assertTrue(
            "The packaged FFmpeg runtime lacks the HLS demuxer required by Vimeo manifests",
            Regex("""(?m)^\s*D\s+hls\s""").containsMatchIn(demuxerSession.output.orEmpty()),
        )
    }
}
