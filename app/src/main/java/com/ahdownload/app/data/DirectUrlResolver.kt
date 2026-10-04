package com.ahdownload.app.data

import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves a URL that already points to a media resource into the same
 * ResolvedMedia/ResolvedFormat contract used by platform resolvers.
 */
class DirectUrlResolver {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(url: String): Result<ResolvedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                val type = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase(Locale.US)
                if (type == "text/html" || type == "application/xhtml+xml") error("HTML_PAGE_NOT_MEDIA")
                val finalUrl = response.request.url.toString()
                val length = response.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                val mediaType = mediaTypeFrom(type, finalUrl)
                if (mediaType == "file") error("DIRECT_URL_NOT_MEDIA")
                val extension = extensionFrom(type, finalUrl) ?: "bin"
                ResolvedMedia(
                    title = titleFromUrl(finalUrl),
                    thumbnail = null,
                    durationSeconds = null,
                    extractor = "DirectUrlResolver",
                    source = finalUrl,
                    formats = listOf(
                        ResolvedFormat(
                            id = "direct",
                            ext = extension,
                            width = null,
                            height = null,
                            abr = null,
                            sizeBytes = length,
                            hasVideo = mediaType == "video",
                            hasAudio = mediaType == "audio",
                            url = finalUrl
                        )
                    )
                )
            }
        }
    }

    private fun mediaTypeFrom(type: String?, url: String): String = when {
        type?.startsWith("video/") == true -> "video"
        type?.startsWith("audio/") == true -> "audio"
        else -> when (extensionFrom(type, url)) {
            "mp4", "webm", "mkv", "mov", "m4v", "avi" -> "video"
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> "audio"
            else -> "file"
        }
    }

    private fun extensionFrom(type: String?, url: String): String? =
        type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getFileExtensionFromUrl(url).lowercase(Locale.US).takeIf { it.isNotBlank() }

    private fun titleFromUrl(url: String): String =
        runCatching { URI(url).path.substringAfterLast('/').ifBlank { "AHDownload file" } }
            .getOrDefault("AHDownload file")
            .replace(Regex("[\\/:*?"<>|]"), "_")
            .take(120)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    }
}
