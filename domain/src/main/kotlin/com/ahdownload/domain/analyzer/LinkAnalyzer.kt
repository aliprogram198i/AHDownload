package com.ahdownload.domain.analyzer

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import java.net.URI
import java.util.Locale

class LinkAnalyzer {
    fun analyze(rawUrl: String): MediaLink? {
        val normalized = rawUrl.trim()
        if (normalized.isBlank()) return null

        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https")) return null

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val kind = detectMediaKind(uri.path)

        val platform = when {
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                MediaPlatform.YouTube
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                MediaPlatform.Instagram
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" ->
                MediaPlatform.Facebook
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                MediaPlatform.TikTok
            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") ->
                MediaPlatform.X
            kind != MediaKind.Unknown -> MediaPlatform.DirectMedia
            else -> MediaPlatform.Unknown
        }

        return MediaLink(
            originalUrl = rawUrl,
            normalizedUrl = normalized,
            platform = platform,
            kind = kind,
        )
    }

    private fun detectMediaKind(path: String?): MediaKind {
        val extension = path
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?: return MediaKind.Unknown

        return when (extension) {
            "mp4", "m4v", "webm", "mov", "mkv" -> MediaKind.Video
            "mp3", "m4a", "aac", "wav", "ogg", "flac" -> MediaKind.Audio
            "jpg", "jpeg", "png", "webp", "gif" -> MediaKind.Image
            else -> MediaKind.Unknown
        }
    }
}
