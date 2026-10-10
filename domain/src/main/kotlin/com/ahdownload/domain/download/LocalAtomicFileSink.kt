package com.ahdownload.domain.download

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * File-backed atomic download sink compatible with Android API 24+.
 *
 * The temporary file is created beside the destination so a rename stays on the
 * same filesystem. The copy/backup path is only used when the platform refuses
 * that rename (for example, when replacing an existing destination).
 */
class LocalAtomicFileSink : AtomicFileSink {

    override suspend fun openTemporary(destinationPath: String, append: Boolean): OutputStream {
        val destination = File(destinationPath).absoluteFile
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create download directory")
        }

        val temporary = temporaryFile(destination)
        if (!append && temporary.exists() && !temporary.delete()) {
            throw IOException("Unable to reset temporary download file")
        }
        if (append && !temporary.isFile) {
            throw IOException("Temporary download file does not exist")
        }
        return FileOutputStream(temporary, append)
    }

    override suspend fun temporarySize(destinationPath: String): Long {
        val temporary = temporaryFile(File(destinationPath).absoluteFile)
        return if (temporary.isFile) temporary.length() else 0L
    }

    override suspend fun commit(destinationPath: String) {
        val destination = File(destinationPath).absoluteFile
        val temporary = temporaryFile(destination)
        if (!temporary.isFile) {
            throw IOException("Temporary download file is missing")
        }

        // Make completed bytes durable before publishing the new filename.
        FileOutputStream(temporary, true).use { it.fd.sync() }

        if (temporary.renameTo(destination)) return

        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create download directory")
        }
        val staging = File(parent, ".${destination.name}.commit")
        val backup = File(parent, ".${destination.name}.backup")
        if (staging.exists() && !staging.delete()) {
            throw IOException("Unable to clear staged download output")
        }

        FileInputStream(temporary).use { input ->
            FileOutputStream(staging, false).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.flush()
                output.fd.sync()
            }
        }

        var destinationBackedUp = false
        try {
            if (destination.exists()) {
                if (backup.exists() && !backup.delete()) {
                    throw IOException("Unable to clear previous destination backup")
                }
                if (!destination.renameTo(backup)) {
                    throw IOException("Unable to preserve the previous destination")
                }
                destinationBackedUp = true
            }

            if (!staging.renameTo(destination)) {
                throw IOException("Unable to publish completed download")
            }
            if (destinationBackedUp) backup.delete()
            temporary.delete()
        } catch (error: Throwable) {
            if (destinationBackedUp && !destination.exists()) {
                backup.renameTo(destination)
            }
            throw error
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    override suspend fun discard(destinationPath: String) {
        val temporary = temporaryFile(File(destinationPath).absoluteFile)
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Unable to discard temporary download file")
        }
    }

    private fun temporaryFile(destination: File): File =
        File(destination.parentFile, destination.name + ".part")

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
