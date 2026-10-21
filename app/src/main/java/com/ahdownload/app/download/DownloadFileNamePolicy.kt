package com.ahdownload.app.download

import java.io.File

/**
 * Keeps final and temporary download filenames below common filesystem component limits.
 * Linux/Android filenames are limited by UTF-8 bytes, not Kotlin String character count.
 */
internal object DownloadFileNamePolicy {
    const val MAX_BASE_NAME_BYTES = 100
    private const val MAX_DESTINATION_COMPONENT_BYTES = 160

    fun safeBaseName(value: String?, fallback: String): String {
        val cleaned = truncateUtf8(sanitize(value), MAX_BASE_NAME_BYTES)
            .trim()
            .trimEnd('.', ' ')
        if (cleaned.isNotBlank()) return cleaned

        return truncateUtf8(sanitize(fallback), MAX_BASE_NAME_BYTES)
            .trim()
            .trimEnd('.', ' ')
            .ifBlank { "AHDownload" }
    }

    /**
     * Normalizes oversized paths already persisted by older app versions.
     * The stable task ID keeps retries on the same recovered path without exposing the
     * original title in diagnostic output or creating a different path on each attempt.
     */
    fun normalizeDestinationPath(destinationPath: String, taskId: String): String {
        val destination = File(destinationPath)
        val name = destination.name
        if (utf8Length(name) <= MAX_DESTINATION_COMPONENT_BYTES) return destinationPath

        val extension = safeExtension(name)
        val stableId = taskId.filter { it.isLetterOrDigit() }.take(24).ifBlank { "task" }
        val parent = destination.absoluteFile.parentFile
        return File(parent, "AHDownload-$stableId$extension").absolutePath
    }

    private fun sanitize(value: String?): String = buildString {
        for (character in value.orEmpty()) {
            append(
                when {
                    character == '\\' || character == '/' || character == ':' ||
                        character == '*' || character == '?' || character == '"' ||
                        character == '<' || character == '>' || character == '|' ||
                        character.code < 0x20 || character.code == 0x7f -> ' '
                    else -> character
                },
            )
        }
    }.trim().trimEnd('.', ' ')

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val result = StringBuilder()
        var offset = 0
        var usedBytes = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val pointBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (usedBytes + pointBytes > maxBytes) break
            result.appendCodePoint(codePoint)
            usedBytes += pointBytes
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun safeExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ".bin"
        val extension = name.substring(dot)
        if (extension.length !in 2..12) return ".bin"
        if (extension.drop(1).any { !it.isLetterOrDigit() || it.code > 0x7f }) return ".bin"
        return extension
    }

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size
}
