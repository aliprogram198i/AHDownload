package com.ahdownload.app.data

import android.webkit.MimeTypeMap
import com.ahdownload.app.domain.MediaFormat
import com.ahdownload.app.domain.MediaInfo
import com.ahdownload.app.domain.MediaType
import com.ahdownload.app.domain.SourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

class DirectUrlResolver : SourceResolver {
    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun canResolve(url: String): Boolean =
        runCatching { URI(url).scheme?.lowercase(Locale.US) in setOf("http", "https") }.getOrDefault(false)

    override suspend fun resolve(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT).header("Accept", "*/*")
                .header("Range", "bytes=0-0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                val type = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase(Locale.US)
                if (type == "text/html" || type == "application/xhtml+xml") error("HTML_PAGE_NOT_MEDIA")
                val finalUrl = response.request.url.toString()
                val length = response.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                val mediaType = mediaTypeFrom(type, finalUrl)
                val title = titleFromUrl(finalUrl)
                val format = MediaFormat(
                    "direct", mediaType, extensionFrom(type, finalUrl), null, null, null, null,
                    mediaType == MediaType.AUDIO, mediaType == MediaType.VIDEO, length, finalUrl, title
                )
                MediaInfo(finalUrl, mediaType, title, null, null, length, listOf(format), true)
            }
        }
    }

    private fun mediaTypeFrom(type: String?, url: String): MediaType = when {
        type?.startsWith("video/") == true -> MediaType.VIDEO
        type?.startsWith("audio/") == true -> MediaType.AUDIO
        else -> when (extensionFrom(type, url)) {
            "mp4", "webm", "mkv", "mov", "m4v", "avi" -> MediaType.VIDEO
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> MediaType.AUDIO
            else -> MediaType.FILE
        }
    }

    private fun extensionFrom(type: String?, url: String): String? =
        type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getFileExtensionFromUrl(url).lowercase(Locale.US).takeIf { it.isNotBlank() }

    private fun titleFromUrl(url: String): String =
        runCatching { URI(url).path.substringAfterLast('/').ifBlank { "AHDownload file" } }
            .getOrDefault("AHDownload file")
            .replace(Regex("[\\/:*?\"<>|]"), "_").take(120)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    }
}
