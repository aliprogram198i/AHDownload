package com.ahdownload.app.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

object StoragePublisher {
    fun publish(context: Context, source: File, displayName: String, mimeType: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", source).toString()
        }

        val resolver = context.contentResolver
        val collection = when {
            mimeType.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, when {
                mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES + "/AHDownload"
                mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC + "/AHDownload"
                mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES + "/AHDownload"
                else -> Environment.DIRECTORY_DOWNLOADS + "/AHDownload"
            })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "storage_output_unavailable" }
                FileInputStream(source).use { input -> input.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri.toString()
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
