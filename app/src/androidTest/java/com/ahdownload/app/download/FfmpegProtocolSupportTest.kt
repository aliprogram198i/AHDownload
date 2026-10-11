package com.ahdownload.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies protocols used by the secure localhost bridge and adaptive manifest demuxers. */
@RunWith(AndroidJUnit4::class)
class FfmpegProtocolSupportTest {
    @Test
    fun ffmpegRuntimeIncludesHttpInputAndHlsDemuxerForTheSecureBridge() {
        val protocolSession = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-protocols"))
        assertTrue(
            "FFmpeg protocol listing must execute successfully: ${protocolSession.failStackTrace.orEmpty()}",
            ReturnCode.isSuccess(protocolSession.returnCode),
        )
        val inputProtocols = protocolSession.output.orEmpty()
            .substringAfter("Input:", missingDelimiterValue = "")
            .substringBefore("Output:", missingDelimiterValue = "")
        assertTrue(
            "The packaged FFmpeg runtime lacks HTTP input required by the secure localhost bridge",
            Regex("""(?m)^\s*http\s*$""").containsMatchIn(inputProtocols),
        )

        val demuxerSession = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-demuxers"))
        assertTrue(
            "FFmpeg demuxer listing must execute successfully: ${demuxerSession.failStackTrace.orEmpty()}",
            ReturnCode.isSuccess(demuxerSession.returnCode),
        )
        assertTrue(
            "The packaged FFmpeg runtime lacks the HLS demuxer required for adaptive manifests",
            Regex("""(?m)^\s*D\s+hls\s""").containsMatchIn(demuxerSession.output.orEmpty()),
        )
    }
}
