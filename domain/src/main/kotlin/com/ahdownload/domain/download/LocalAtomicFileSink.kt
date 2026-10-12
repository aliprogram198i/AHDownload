package com.ahdownload.domain.download

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * File-backed atomic download sink compatible with Android API 24+.
 * Partial, staging and backup files are scoped to a transfer ID so concurrent jobs
 * cannot append to or clean up one another's temporary work.
 */
class LocalAtomicFileSink : AtomicFileSink {

    override suspend fun openTemporary(
        destinationPath: String,
        append: Boolean,
        transferId: String?,
    ): OutputStream {
        val destination = File(destinationPath).absoluteFile
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create download directory")
        }

        val temporary = resolveTemporaryFile(destinationPath, transferId)
        if (!append && temporary.exists() && !temporary.delete()) {
            throw IOException("Unable to reset temporary download file")
        }
        if (append && !temporary.isFile) throw IOException("Temporary download file does not exist")
        return FileOutputStream(temporary, append)
    }

    override suspend fun temporarySize(destinationPath: String, transferId: String?): Long {
        val temporary = resolveTemporaryFile(destinationPath, transferId)
        return if (temporary.isFile) temporary.length() else 0L
    }

    override suspend fun commit(destinationPath: String, transferId: String?) {
        val destination = File(destinationPath).absoluteFile
        val temporary = resolveTemporaryFile(destinationPath, transferId)
        if (!temporary.isFile) throw IOException("Temporary download file is missing")

        FileOutputStream(temporary, true).use { it.fd.sync() }
        if (temporary.renameTo(destination)) return

        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create download directory")
        }
        val staging = DownloadTemporaryFiles.stagingFile(destinationPath, transferId)
        val backup = DownloadTemporaryFiles.backupFile(destinationPath, transferId)
        if (staging.exists() && !staging.delete()) throw IOException("Unable to clear staged download output")

        var destinationBackedUp = false
        try {
            // Include the staging copy in this cleanup scope too. A write failure must not
            // leave a stale staging file that a later attempt could mistake for a valid output.
            FileInputStream(temporary).use { input ->
                FileOutputStream(staging, false).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                    output.flush()
                    output.fd.sync()
                }
            }

            if (destination.exists()) {
                if (backup.exists() && !backup.delete()) throw IOException("Unable to clear previous destination backup")
                if (!destination.renameTo(backup)) throw IOException("Unable to preserve the previous destination")
                destinationBackedUp = true
            }
            if (!staging.renameTo(destination)) throw IOException("Unable to publish completed download")
            if (destinationBackedUp) backup.delete()
            temporary.delete()
        } catch (error: Throwable) {
            if (destinationBackedUp && !destination.exists()) backup.renameTo(destination)
            throw error
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    override suspend fun discard(destinationPath: String, transferId: String?) {
        val temporary = resolveTemporaryFile(destinationPath, transferId)
        if (temporary.exists() && !temporary.delete()) throw IOException("Unable to discard temporary download file")
    }

    private fun resolveTemporaryFile(destinationPath: String, transferId: String?): File {
        val scoped = DownloadTemporaryFiles.temporaryFile(destinationPath, transferId)
        if (transferId.isNullOrBlank()) return scoped

        val legacy = DownloadTemporaryFiles.legacyTemporaryFile(destinationPath)
        if (!scoped.exists() && legacy.isFile) {
            try {
                if (!legacy.renameTo(scoped)) {
                    try {
                        legacy.copyTo(scoped, overwrite = false)
                    } catch (error: Exception) {
                        scoped.delete()
                        throw error
                    }
                    if (!legacy.delete()) {
                        scoped.delete()
                        throw IOException("Unable to migrate legacy temporary download file")
                    }
                }
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("Unable to migrate legacy temporary download file", error)
            }
        }
        return scoped
    }

    private companion object { const val COPY_BUFFER_SIZE = 64 * 1024 }
}
