package com.ahdownload.app.download

import com.ahdownload.domain.download.AudioOutputFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device audio extraction/transcoding.
 *
 * The downloader first acquires a local media source. This component then
 * extracts the first audio stream and encodes it to the exact user-selected
 * output container/codec.
 */
class AudioTranscoder {

    suspend fun transcode(
        inputFile: File,
        outputFile: File,
        outputFormat: AudioOutputFormat,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!inputFile.isFile || inputFile.length() <= 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio source file is missing or empty"),
            )
        }

        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("Unable to create audio output directory"),
                )
            }
        }
        outputFile.delete()

        val arguments = buildArguments(inputFile, outputFile, outputFormat)

        runCatching {
            val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
            val returnCode = session.returnCode
            if (!ReturnCode.isSuccess(returnCode)) {
                val detail = session.failStackTrace
                    ?.takeIf { it.isNotBlank() }
                    ?: session.output
                        ?.takeIf { it.isNotBlank() }
                        ?.takeLast(MAX_ERROR_OUTPUT_CHARS)
                    ?: "FFmpeg failed with return code " + (returnCode ?: "unknown")
                throw IllegalStateException(detail)
            }

            if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw IllegalStateException("Audio conversion completed without a valid output file")
            }

            outputFile
        }
    }

    private fun buildArguments(
        inputFile: File,
        outputFile: File,
        outputFormat: AudioOutputFormat,
    ): List<String> {
        val base = mutableListOf(
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-y",
            "-i",
            inputFile.absolutePath,
            "-map",
            "0:a:0",
            "-vn",
            "-sn",
            "-dn",
        )

        when (outputFormat) {
            AudioOutputFormat.Mp3 -> base += listOf(
                "-c:a", "libmp3lame",
                "-b:a", "192k",
                outputFile.absolutePath,
            )

            AudioOutputFormat.M4a -> base += listOf(
                "-c:a", "aac",
                "-b:a", "192k",
                "-movflags", "+faststart",
                outputFile.absolutePath,
            )

            AudioOutputFormat.Aac -> base += listOf(
                "-c:a", "aac",
                "-b:a", "192k",
                "-f", "adts",
                outputFile.absolutePath,
            )

            AudioOutputFormat.Opus -> base += listOf(
                "-c:a", "libopus",
                "-b:a", "128k",
                "-vbr", "on",
                "-application", "audio",
                outputFile.absolutePath,
            )

            AudioOutputFormat.Ogg -> base += listOf(
                "-c:a", "libvorbis",
                "-b:a", "192k",
                outputFile.absolutePath,
            )

            AudioOutputFormat.Flac -> base += listOf(
                "-c:a", "flac",
                "-compression_level", "5",
                outputFile.absolutePath,
            )

            AudioOutputFormat.Wav -> base += listOf(
                "-c:a", "pcm_s16le",
                "-ar", "44100",
                "-ac", "2",
                "-f", "wav",
                outputFile.absolutePath,
            )
        }

        return base
    }

    private companion object {
        const val MAX_ERROR_OUTPUT_CHARS = 3000
    }
}
