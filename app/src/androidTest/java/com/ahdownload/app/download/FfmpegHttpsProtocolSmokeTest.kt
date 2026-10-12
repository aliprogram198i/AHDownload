package com.ahdownload.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the production HTTPS workaround end-to-end: Android fetches an HTTPS
 * media resource and exposes it to native FFmpeg over loopback HTTP.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegHttpsProtocolSmokeTest {
    @Test
    fun bundledFfmpegCanReadHttpsMediaThroughLoopbackBridge() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.cacheDir, "ffmpeg-https-bridge-smoke.mp4")
        output.delete()

        FfmpegManifestHttpsBridge(
            requestHeaders = emptyMap(),
            cookie = null,
        ).use { bridge ->
            val session = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-hide_banner",
                    "-loglevel", "error",
                    "-nostdin",
                    "-y",
                    "-i", bridge.resourceUrl("https://www.w3schools.com/html/mov_bbb.mp4"),
                    "-map", "0:v:0",
                    "-c:v", "copy",
                    "-an",
                    "-movflags", "+faststart",
                    "-f", "mp4",
                    output.absolutePath,
                ),
            )
            assertTrue(
                "FFmpeg could not read HTTPS media through loopback HTTP. " +
                    "exit=${session.returnCode}; output=${session.output.orEmpty().take(1000)}",
                ReturnCode.isSuccess(session.returnCode),
            )
        }

        try {
            assertTrue(
                "Bridged HTTPS request produced no valid output media.",
                output.isFile && output.length() > 100_000L,
            )
        } finally {
            output.delete()
        }
    }
}
