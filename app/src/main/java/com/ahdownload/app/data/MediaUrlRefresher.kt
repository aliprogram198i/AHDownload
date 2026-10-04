package com.ahdownload.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaUrlRefresher(private val context: Context) {
    suspend fun refresh(
        sourceUrl: String,
        extension: String,
        mergeRequired: Boolean,
        excludeUrl: String? = null
    ): Result<ResolvedFormat> = withContext(Dispatchers.IO) {
        runCatching {
            val host = Uri.parse(sourceUrl).host.orEmpty().lowercase()
            require(isSupportedPlatform(host)) { "URL_REFRESH_UNSUPPORTED" }
            val media = EmbeddedPlatformResolver(context).resolve(sourceUrl).getOrThrow()
            val formats = media.formats
            val candidates = formats.filter { it.url.isNotBlank() && it.url != excludeUrl }
            candidates.firstOrNull { format ->
                format.ext.equals(extension, ignoreCase = true) &&
                    format.mergeRequired == mergeRequired
            } ?: candidates.firstOrNull { it.mergeRequired == mergeRequired }
            ?: candidates.firstOrNull()
            ?: error("NO_REFRESHED_FORMAT")
        }
    }

    private fun isSupportedPlatform(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fb.watch"
}
