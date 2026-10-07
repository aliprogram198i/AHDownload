package com.ahdownload.app.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Publishes a completed private download into the user's public media collection on Android 10+. */
class MediaStorePublisher(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    suspend fun publish(file: File): Result<Uri> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext Result.failure(IOException("MediaStore publishing requires Android 10+"))
        }
        if (!file.isFile || file.length() <= 0L) {
            return@withContext Result.failure(IOException("Completed media file is missing or empty"))
        }

        val metadata = metadataFor(file.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, metadata.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, metadata.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = metadata.collection
        val uri = resolver.insert(collection, values)
            ?: return@withContext Result.failure(IOException("MediaStore insert failed"))

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(file).use { input ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            } ?: throw IOException("Unable to open MediaStore output stream")

            val complete = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            check(resolver.update(uri, complete, null, null) == 1) {
                "MediaStore finalize failed"
            }
            check(file.delete()) { "Private source file could not be removed after publish" }
            Result.success(uri)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            Result.failure(error)
        }
    }

    private data class MediaMetadata(
        val collection: Uri,
        val mimeType: String,
        val relativePath: String,
    )

    private fun metadataFor(name: String): MediaMetadata {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v", "webm", "mkv", "mov", "3gp", "avi" -> MediaMetadata(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                mimeType = when (extension) {
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    "mov" -> "video/quicktime"
                    "mkv" -> "video/x-matroska"
                    "avi" -> "video/x-msvideo"
                    "3gp" -> "video/3gpp"
                    "m4v" -> "video/mp4"
                    else -> "video/*"
                },
                relativePath = "Movies/AHDownload",
            )
            "mp3", "m4a", "aac", "ogg", "flac", "wav" -> MediaMetadata(
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                mimeType = when (extension) {
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "aac" -> "audio/aac"
                    "ogg" -> "audio/ogg"
                    "flac" -> "audio/flac"
                    "wav" -> "audio/wav"
                    else -> "audio/*"
                },
                relativePath = "Music/AHDownload",
            )
            "jpg", "jpeg", "png", "webp", "gif" -> MediaMetadata(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                mimeType = when (extension) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/*"
                },
                relativePath = "Pictures/AHDownload",
            )
            else -> MediaMetadata(
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                mimeType = "application/octet-stream",
                relativePath = "Download/AHDownload",
            )
        }
    }
}