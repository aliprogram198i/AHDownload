package com.ahdownload.app.download

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime capability gate for the packaged FFmpeg native library.
 *
 * Real public HTTPS fixtures verify that FFmpeg can open a progressive MP4, HLS, and DASH
 * source and read video packets. Dependency declarations alone do not prove TLS support.
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
                "exit=${session.returnCode}; output=${sanitize(output).take(1800)}",
            hasHttps,
        )
    }

    @Test(timeout = 240_000L)
    fun bundledFfmpegCanReadRealHttpsMediaFixtures() {
        val failures = mutableListOf<String>()
        var passed = 0

        for (fixture in FIXTURES) {
            val session = runCatching {
                FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-hide_banner",
                        "-loglevel", "error",
                        "-rw_timeout", "15000000",
                        "-i", fixture.url,
                        "-t", "2",
                        "-map", "0:v:0",
                        "-an",
                        "-c:v", "copy",
                        "-f", "null",
                        "-",
                    ),
                )
            }.getOrNull()

            val success = session != null && ReturnCode.isSuccess(session.returnCode)
            val detail = if (success) {
                "PASS | ${fixture.id} | source_type=${fixture.type} | exit=${session?.returnCode}"
            } else {
                val output = session?.let {
                    buildString {
                        append(runCatching { it.allLogsAsString }.getOrDefault(""))
                        append('\n')
                        append(it.output.orEmpty())
                    }
                }.orEmpty()
                "FAIL | ${fixture.id} | source_type=${fixture.type} | exit=${session?.returnCode ?: "execution_exception"} | detail=${sanitize(output).take(500)}"
            }

            Log.i(TAG, detail)
            if (success) passed++ else failures += detail
        }

        val summary = "FFmpeg HTTPS media matrix: $passed/${FIXTURES.size} passed."
        Log.i(TAG, summary)
        assertTrue(
            "$summary Failures: ${failures.joinToString(" || ")}",
            failures.isEmpty(),
        )
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("""https?://[^\s]+"""), "[url-redacted]")
            .replace(Regex("""(?i)(token|sig|signature|auth|cookie|key)=?[^&\s]+"""), "[parameter-redacted]")
            .replace('\n', ' ')
            .take(1800)

    private data class Fixture(
        val id: String,
        val type: String,
        val url: String,
    )

    private companion object {
        const val TAG = "AH_FFMPEG_HTTPS_MATRIX"

        val FIXTURES = listOf(
            Fixture(
                id = "mux_hls",
                type = "HLS",
                url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            ),
            Fixture(
                id = "apple_hls",
                type = "HLS",
                url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
            ),
            Fixture(
                id = "akamai_dash",
                type = "DASH",
                url = "https://dash.akamaized.net/akamai/test/caption_test/elephants_dream/elephants_dream_480p_heaac5_1.mpd",
            ),
            Fixture(
                id = "mozilla_mp4",
                type = "progressive MP4",
                url = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            ),
        )
    }
}
