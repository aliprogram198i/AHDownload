package com.ahdownload.app.download

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Removes URLs and credential-like values before FFmpeg output is included in
 * diagnostics. Keep protocol error codes/messages so failures remain actionable.
 */
internal fun sanitizeFfmpegDiagnostic(rawOutput: String): String {
    if (rawOutput.isBlank()) return "no FFmpeg diagnostic output"

    val lines = rawOutput.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { line ->
            line
                .replace(
                    Regex("(?i)\\b(cookie|set-cookie|authorization|proxy-authorization)\\b\\s*[:=]\\s*[^\\r\\n]*"),
                    "$1=[redacted]",
                )
                .replace(
                    Regex("(?i)\\b(token|po_token|access_token|refresh_token|signature|sig|visitor_data|key|expires|credential|policy|jwt)\\b(\\s*[:=]\\s*)[^\\s&;,]+"),
                    "$1$2[redacted]",
                )
                .replace(
                    Regex("(?i)([?&](?:token|po_token|access_token|refresh_token|signature|sig|key|expires|credential|policy|jwt)=)[^&\\s]+"),
                    "$1[redacted]",
                )
                .replace(
                    Regex("(?i)https?://[^\\s\\"'<>]+"),
                    "[url redacted]",
                )
        }
        .takeLast(8)
        .map { it.take(240) }
        .toList()

    return lines.joinToString(" | ").take(1000).ifBlank { "no FFmpeg diagnostic output" }
}

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

        data class AttemptResult(
            val success: Boolean,
            val returnCode: String,
            val diagnostic: String,
        )

        fun execute(arguments: List<String>): AttemptResult =
            try {
                val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
                val logs = runCatching { session.allLogsAsString }
                    .getOrElse { session.output.orEmpty() }
                val returnCode = session.returnCode?.toString() ?: "unknown"
                val commandSucceeded = ReturnCode.isSuccess(session.returnCode)
                val hasOutput = temp.isFile && temp.length() > 0L
                val diagnostic = when {
                    !commandSucceeded -> sanitizeFfmpegDiagnostic(logs)
                    !hasOutput -> "FFmpeg completed without producing a non-empty output file."
                    else -> sanitizeFfmpegDiagnostic(logs)
                }
                AttemptResult(
                    success = commandSucceeded && hasOutput,
                    returnCode = returnCode,
                    diagnostic = diagnostic,
                )
            } catch (error: Exception) {
                AttemptResult(
                    success = false,
                    returnCode = "execution_exception",
                    diagnostic = sanitizeFfmpegDiagnostic(error.message.orEmpty()),
                )
            }

        val copyAttempt = execute(base)
        var finalAttempt = copyAttempt

        // Retry without touching video when a source audio codec cannot be copied
        // cleanly into the selected Matroska output by the bundled FFmpeg build.
        if (!copyAttempt.success) {
            temp.delete()
            val fallback = base.toMutableList().also { args ->
                val audioCodecIndex = args.indexOf("-c:a")
                if (audioCodecIndex >= 0 && audioCodecIndex + 1 < args.size) {
                    args[audioCodecIndex + 1] = "aac"
                    args.add(audioCodecIndex + 2, "-b:a")
                    args.add(audioCodecIndex + 3, "192k")
                }
            }
            finalAttempt = execute(fallback)
        }

        if (!finalAttempt.success) {
            temp.delete()
            val copyDetail = "copy exit=${copyAttempt.returnCode}: ${copyAttempt.diagnostic}"
            val retryDetail = "AAC-retry exit=${finalAttempt.returnCode}: ${finalAttempt.diagnostic}"
            return@withContext Result.failure(
                IllegalStateException(
                    "FFmpeg manifest acquisition failed; $copyDetail; $retryDetail".take(1800),
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
