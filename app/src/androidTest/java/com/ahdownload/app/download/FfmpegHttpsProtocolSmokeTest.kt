package com.ahdownload.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime capability gate: manifest downloads require FFmpeg's HTTPS protocol.
 * This verifies the packaged native build, not merely the Gradle dependency declaration.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegHttpsProtocolSmokeTest {
    @Test
    fun bundledFfmpegIncludesHttpsProtocol() {
        val session = FFmpegKit.executeWithArguments(
            arrayOf("-hide_banner", "-protocols"),
        )
        val output = buildString {
            append(runCatching { session.allLogsAsString }.getOrDefault(""))
            append('\n')
            append(session.output.orEmpty())
        }
        val hasHttps = output.lineSequence().any { it.trim().equals("https", ignoreCase = true) }

        assertTrue(
            "Bundled FFmpeg must expose the HTTPS protocol for HLS/DASH acquisition. " +
                "exit=${session.returnCode}; output=${output.take(3000)}",
            hasHttps,
        )
    }
}
