package com.ahdownload.app.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import com.ahdownload.domain.download.DownloadRecord
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaAudioExtractor(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun extractAndPublish(record: DownloadRecord): Result<Uri> = withContext(Dispatchers.IO) {
        val workFile = extractToLocalM4a(record).getOrElse { return@withContext Result.failure(it) }
        MediaStorePublisher(appContext).publish(workFile)
    }

    private fun extractToLocalM4a(record: DownloadRecord): Result<File> {
        val outputDirectory = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appContext.filesDir,
            "AHDownload/Studio",
        )
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            return Result.failure(IllegalStateException("Unable to create Studio output directory"))
        }

        val baseName = sanitize(
            record.task.displayName
                ?.substringBeforeLast('.', record.task.displayName.orEmpty())
                .orEmpty()
                .ifBlank { "AHDownload-${record.task.id.take(8)}" },
        )
        var index = 0
        val output = generateOutputFile(outputDirectory, baseName) { index++ }

        val extractor = MediaExtractor()
        var outputMuxer: MediaMuxer? = null
        var descriptor: android.os.ParcelFileDescriptor? = null
        var started = false
        return try {
            val rawUri = record.destinationUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (rawUri != null) {
                descriptor = appContext.contentResolver.openFileDescriptor(rawUri, "r")
                    ?: error("Unable to open completed media")
                extractor.setDataSource(descriptor!!.fileDescriptor)
            } else {
                val source = File(record.task.destinationPath)
                require(source.isFile) { "Completed media file is missing" }
                extractor.setDataSource(source.absolutePath)
            }

            val audioTrack = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return Result.failure(IllegalArgumentException("No audio track found"))

            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime == "audio/mp4a-latm") {
                "Audio extraction export requires AAC audio, found $mime"
            }

            outputMuxer = MediaMuxer(
                output.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val outputTrack = outputMuxer!!.addTrack(format)
            outputMuxer!!.start()
            started = true
            extractor.selectTrack(audioTrack)

            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(
                    0,
                    size,
                    extractor.sampleTime.coerceAtLeast(0L),
                    extractor.sampleFlags,
                )
                outputMuxer!!.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }

            outputMuxer!!.stop()
            started = false
            require(output.isFile && output.length() > 0L) { "Extracted audio file is empty" }
            Result.success(output)
        } catch (error: Throwable) {
            output.delete()
            Result.failure(error)
        } finally {
            runCatching {
                if (started) outputMuxer?.stop()
            }
            runCatching { outputMuxer?.release() }
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun generateOutputFile(
        directory: File,
        baseName: String,
        nextIndex: () -> Int,
    ): File {
        while (true) {
            val index = nextIndex()
            val suffix = if (index == 0) "" else "-$index"
            val candidate = File(directory, "$baseName-audio$suffix.m4a")
            if (!candidate.exists()) return candidate
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), " ")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifBlank { "AHDownload" }
}
