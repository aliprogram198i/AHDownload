package com.ahdownload.app.download

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Muxes separate video/audio representations into one playable file without
 * re-encoding when the source codecs are already compatible with the target.
 */
class MediaVideoAudioMuxer {
    suspend fun mux(
        videoFile: File,
        audioFile: File,
        outputFile: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!videoFile.isFile || videoFile.length() <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Video source is missing or empty"))
        }
        if (!audioFile.isFile || audioFile.length() <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Audio source is missing or empty"))
        }
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("Unable to create mux output directory"))
            }
        }

        val temp = File(outputFile.parentFile, "." + outputFile.name + ".muxing")
        temp.delete()
        outputFile.delete()

        val arguments = buildList {
            addAll(
                listOf(
                    "-hide_banner",
                    "-loglevel", "error",
                    "-nostdin",
                    "-y",
                    "-i", videoFile.absolutePath,
                    "-i", audioFile.absolutePath,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "copy",
                ),
            )
            if (outputFile.extension.equals("mp4", ignoreCase = true)) {
                addAll(listOf("-movflags", "+faststart"))
            }
            addAll(listOf("-shortest", temp.absolutePath))
        }.toTypedArray()

        val success = runCatching {
            val session = FFmpegKit.executeWithArguments(arguments)
            ReturnCode.isSuccess(session.returnCode)
        }.getOrDefault(false)

        if (!success || !temp.isFile || temp.length() <= 0L) {
            temp.delete()
            return@withContext Result.failure(IllegalStateException("FFmpeg could not mux the selected video and audio streams"))
        }

        val published = temp.renameTo(outputFile) || runCatching {
            temp.copyTo(outputFile, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)

        if (!published || !outputFile.isFile || outputFile.length() <= 0L) {
            temp.delete()
            outputFile.delete()
            return@withContext Result.failure(IllegalStateException("Unable to publish the muxed media file"))
        }

        Result.success(outputFile)
    }
}
