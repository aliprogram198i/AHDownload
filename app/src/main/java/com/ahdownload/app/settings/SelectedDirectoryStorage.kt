package com.ahdownload.app.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

object SelectedDirectoryStorage {
    fun copyFromLocal(
        context: Context,
        source: File,
        treeUri: Uri,
        displayName: String,
    ): Uri {
        require(source.isFile) { "Source file does not exist" }
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        require(parentId.isNotBlank()) { "Invalid tree URI" }

        val parentUri = buildParentDocumentUri(treeUri, parentId)
        val name = uniqueDisplayName(resolver, treeUri, parentId, displayName)
        val mimeType = mimeTypeFor(name)
        val destination = DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw IOException("Unable to create destination document")

        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            } ?: throw IOException("Unable to open destination for writing")
            return destination
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }


    /**
     * Returns the document URI for the selected tree's root (or the supplied document ID).
     *
     * A tree URI alone is not a valid parent URI for DocumentsContract.createDocument().
     * The URI must contain both the tree grant and a document path.
     */
    internal fun buildParentDocumentUri(treeUri: Uri, documentId: String): Uri {
        require(DocumentsContract.isTreeUri(treeUri)) { "Invalid tree URI" }
        require(!treeUri.authority.isNullOrBlank()) { "Missing authority" }
        require(documentId.isNotBlank()) { "Invalid tree document ID" }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    fun createFolder(context: Context, treeUri: Uri, folderName: String): Uri? {
        val safeName = folderName.trim().replace(Regex("""[\\/:*?"<>|]+"""), " ").take(80)
        if (safeName.isBlank()) return null
        val resolver = context.contentResolver
        val parentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val parentUri = runCatching { buildParentDocumentUri(treeUri, parentId) }.getOrNull() ?: return null
        return DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            safeName,
        )
    }

    private fun uniqueDisplayName(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentId: String,
        requested: String,
    ): String {
        val existing = mutableSetOf<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext() && index >= 0) existing += cursor.getString(index)
        }

        if (requested !in existing) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base-$index$extension"
            if (candidate !in existing) return candidate
            index++
        }
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
