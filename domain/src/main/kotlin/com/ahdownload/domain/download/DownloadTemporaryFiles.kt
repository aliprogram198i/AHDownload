package com.ahdownload.domain.download

import java.io.File
import java.security.MessageDigest

/** Shared naming rules for resumable transfer files and crash recovery. */
object DownloadTemporaryFiles {
    fun temporaryFile(destinationPath: String, transferId: String?): File {
        val destination = File(destinationPath).absoluteFile
        val token = transferToken(transferId)
        val name = if (token == null) destination.name + ".part"
        else destination.name + "." + token + ".part"
        return File(destination.parentFile, name)
    }

    fun legacyTemporaryFile(destinationPath: String): File {
        val destination = File(destinationPath).absoluteFile
        return File(destination.parentFile, destination.name + ".part")
    }

    fun stagingFile(destinationPath: String, transferId: String?): File =
        auxiliaryFile(destinationPath, transferId, "commit")

    fun backupFile(destinationPath: String, transferId: String?): File =
        auxiliaryFile(destinationPath, transferId, "backup")

    /** Candidate partial files that may safely be resumed after a worker/process interruption. */
    fun recoverableTemporaryFiles(task: DownloadTask): List<File> {
        val paths = when (task.processingMode) {
            DownloadProcessingMode.Direct -> {
                if (task.streamingManifest) emptyList()
                else listOf(task.destinationPath to task.id)
            }
            DownloadProcessingMode.ExtractAudio -> {
                if (task.streamingManifest) emptyList()
                else listOf((task.destinationPath + ".source." + task.id.take(8)) to task.id)
            }
            DownloadProcessingMode.MuxVideoAudio -> buildList {
                if (!task.streamingManifest) {
                    add((task.destinationPath + ".video." + task.id.take(8)) to task.id)
                }
                if (!task.companionAudioStreamingManifest) {
                    add((task.destinationPath + ".audio." + task.id.take(8)) to (task.id + "-audio"))
                }
            }
        }
        return paths.flatMap { (path, id) ->
            listOf(temporaryFile(path, id), legacyTemporaryFile(path))
        }.distinctBy { it.absolutePath }
    }

    internal fun transferToken(transferId: String?): String? {
        val value = transferId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun auxiliaryFile(destinationPath: String, transferId: String?, suffix: String): File {
        val destination = File(destinationPath).absoluteFile
        val token = transferToken(transferId)
        val name = if (token == null) "." + destination.name + "." + suffix
        else "." + destination.name + "." + token + "." + suffix
        return File(destination.parentFile, name)
    }
}
