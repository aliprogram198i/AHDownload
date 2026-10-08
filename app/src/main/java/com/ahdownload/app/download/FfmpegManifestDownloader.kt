package com.ahdownload.app.download

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Acquires VOD HLS/DASH sources by letting FFmpeg resolve the manifest and
 * assemble the available streams into a codec-safe Matroska container.
 *
 * Cookies are accepted only as an in-memory execution parameter. They are never
 * persisted to WorkManager input or written to diagnostics.
 */
class FfmpegManifestDownloader {

    suspend fun download(
        sourceUrl: String,
        outputFile: File,
        requestHeaders: Map<String, String> = emptyMap(),
        cookie: String? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid streaming manifest URL"),
            )
        }

        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("Unable to create manifest output directory"),
                )
            }
        }

        val temp = File(
            outputFile.parentFile,
            ".${outputFile.name}.manifesting.mkv",
        )
        temp.delete()

        val safeHeaders = requestHeaders.filterKeys(::isSafeHeader)
        val headerBlock = buildString {
            safeHeaders.entries
                .filterNot { it.key.equals("User-Agent", true) }
                .forEach { (name, value) ->
                    append(name).append(": ").append(value).append("\r\n")
                }
            cookie?.takeIf { it.isNotBlank() }?.let {
                append("Cookie: ").append(it).append("\r\n")
            }
        }

        val userAgent = safeHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", true) }
            ?.value
            ?: DEFAULT_USER_AGENT

        val base = mutableListOf(
            "-hide_banner",
            "-loglevel", "error",
            "-nostdin",
            "-y",
            "-rw_timeout", "30000000",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5",
            "-user_agent", userAgent,
        )
        if (headerBlock.isNotBlank()) {
            base += listOf("-headers", headerBlock)
        }
        base += listOf(
            "-i", sourceUrl,
            "-map", "0:v:0?",
            "-map", "0:a:0?",
            "-sn",
            "-dn",
            "-c:v", "copy",
            "-c:a", "copy",
            "-f", "matroska",
            temp.absolutePath,
        )

        fun execute(arguments: List<String>): Boolean =
            runCatching {
                val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
                ReturnCode.isSuccess(session.returnCode) &&
                    temp.isFile &&
                    temp.length() > 0L
            }.getOrDefault(false)

        var success = execute(base)

        // Retry without touching video when a source audio codec cannot be copied
        // cleanly into the selected Matroska output by the bundled FFmpeg build.
        if (!success) {
            temp.delete()
            val fallback = base.toMutableList().also { args ->
                val audioCodecIndex = args.indexOf("-c:a")
                if (audioCodecIndex >= 0 && audioCodecIndex + 1 < args.size) {
                    args[audioCodecIndex + 1] = "aac"
                    args.add(audioCodecIndex + 2, "-b:a")
                    args.add(audioCodecIndex + 3, "192k")
                }
            }
            success = execute(fallback)
        }

        if (!success) {
            temp.delete()
            return@withContext Result.failure(
                IllegalStateException(
                    "FFmpeg could not acquire the HLS/DASH media source",
                ),
            )
        }

        coroutineContext.ensureActive()

        outputFile.delete()
        val published = temp.renameTo(outputFile) || runCatching {
            temp.copyTo(outputFile, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)

        if (!published || !outputFile.isFile || outputFile.length() <= 0L) {
            temp.delete()
            outputFile.delete()
            return@withContext Result.failure(
                IllegalStateException("Unable to publish the manifest media output"),
            )
        }

        Result.success(outputFile)
    }

    private fun isSafeHeader(name: String): Boolean =
        name.equals("User-Agent", true) ||
            name.equals("Referer", true) ||
            name.equals("Origin", true) ||
            name.equals("Accept", true) ||
            name.equals("Accept-Language", true) ||
            name.startsWith("Sec-Fetch-", true) ||
            name.startsWith("Sec-CH-UA", true) ||
            name.equals("X-Goog-Visitor-Id", true) ||
            name.equals("X-YouTube-Client-Name", true) ||
            name.equals("X-YouTube-Client-Version", true)

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
