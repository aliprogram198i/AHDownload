package com.ahdownload.app.download

/**
 * Maps storage exceptions to stable, privacy-safe diagnostic categories.
 * Raw exception messages and paths are intentionally not exported to the report.
 */
object StorageFailureDiagnostics {
    fun classify(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .toList()
        val types = chain.joinToString(" ") { it.javaClass.simpleName.lowercase() }
        val messages = chain.joinToString(" ") { it.message.orEmpty().lowercase() }
        val evidence = "$types $messages"

        return when {
            listOf("enospc", "no space left", "not enough space", "insufficient storage")
                .any { evidence.contains(it) } -> "NO_SPACE_AVAILABLE"
            listOf("permission denied", "eacces", "access denied").any { evidence.contains(it) } ||
                chain.any { it is SecurityException } -> "ACCESS_DENIED"
            listOf("read-only file system", "erofs").any { evidence.contains(it) } ->
                "READ_ONLY_STORAGE"
            listOf("no such file", "enoent", "directory unavailable").any { evidence.contains(it) } ->
                "STAGING_PATH_UNAVAILABLE"
            listOf("file name too long", "enametoolong").any { evidence.contains(it) } ->
                "INVALID_DESTINATION_NAME"
            listOf("quota exceeded", "edquot").any { evidence.contains(it) } ->
                "STORAGE_QUOTA_EXCEEDED"
            chain.any { it is java.io.FileNotFoundException } ->
                "FILE_UNAVAILABLE_OR_STORAGE_IO"
            chain.any { it is java.io.IOException } -> "IO_FAILURE"
            else -> "UNEXPECTED_STORAGE_FAILURE"
        }
    }

    private const val MAX_CAUSE_DEPTH = 4
}
