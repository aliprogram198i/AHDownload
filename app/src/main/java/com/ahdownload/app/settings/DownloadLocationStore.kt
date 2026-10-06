package com.ahdownload.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadLocation(
    val uri: Uri?,
    val displayName: String,
    val isCustom: Boolean,
    val isAccessible: Boolean,
)

class DownloadLocationStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _location = MutableStateFlow(loadLocation())
    val location: StateFlow<DownloadLocation> = _location.asStateFlow()

    fun saveTreeUri(uri: Uri) {
        val resolver = appContext.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        _location.value = loadLocation()
    }

    fun resetToDefault() {
        persistedUri()?.let { uri ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
        _location.value = loadLocation()
    }

    fun persistedUri(): Uri? =
        preferences.getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun hasAccessibleCustomLocation(): Boolean =
        persistedUri()?.let { isAccessible(it) } == true

    private fun loadLocation(): DownloadLocation {
        val uri = persistedUri()
        if (uri == null) {
            val directory = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            return DownloadLocation(
                uri = null,
                displayName = directory?.absolutePath ?: "مسار التطبيق الافتراضي",
                isCustom = false,
                isAccessible = directory?.let { it.exists() || it.mkdirs() } == true,
            )
        }
        val name = queryDisplayName(uri) ?: uri.toString()
        return DownloadLocation(
            uri = uri,
            displayName = name,
            isCustom = true,
            isAccessible = isAccessible(uri),
        )
    }

    private fun isAccessible(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission } &&
            runCatching {
                appContext.contentResolver.query(
                    DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)),
                    arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null,
                    null,
                    null,
                )?.use { it.moveToFirst() } == true
            }.getOrDefault(false)

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    companion object {
        private const val PREFERENCES = "ahdownload_settings"
        private const val KEY_TREE_URI = "download_tree_uri"
    }
}
