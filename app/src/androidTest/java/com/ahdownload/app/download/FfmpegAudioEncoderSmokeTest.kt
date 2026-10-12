package com.ahdownload.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The HTTPS-enabled FFmpeg flavor must not silently remove encoders exposed by
 * AHDownload's audio extraction UI. This inspects the native runtime in the APK.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegAudioEncoderSmokeTest {
    @Test
    fun bundledFfmpegIncludesEveryAdvertisedAudioEncoder() {
        val session = FFmpegKit.executeWithArguments(
            arrayOf("-hide_banner", "-encoders"),
        )
        val output = buildString {
            append(runCatching { session.allLogsAsString }.getOrDefault(""))
            append('\n')
            append(session.output.orEmpty())
        }
        val availableNames = output.lineSequence()
            .map { line -> line.trim().split(Regex("\\s+")) }
            .flatten()
            .toSet()
        val requiredEncoders = setOf(
            "aac",
            "libmp3lame",
            "libopus",
            "libvorbis",
            "flac",
            "pcm_s16le",
        )
        val missing = requiredEncoders - availableNames

        assertTrue(
            "Bundled FFmpeg is missing audio encoders advertised by AHDownload: " +
                "${missing.sorted().joinToString()}; exit=${session.returnCode}; " +
                "encoder listing=${output.take(5000)}",
            session.returnCode?.let { it.value == 0 } == true && missing.isEmpty(),
        )
    }
}
