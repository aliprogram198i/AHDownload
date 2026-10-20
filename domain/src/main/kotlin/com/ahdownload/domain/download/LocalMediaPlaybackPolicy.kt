package com.ahdownload.domain.download

import com.ahdownload.domain.model.MediaKind
import java.net.URI
import java.util.Locale

/** Media formats intentionally supported by the in-app local player. */
enum class LocalMediaPlaybackType {
    AUDIO,
    VIDEO,
}

/**
 * Resolves the output type of a completed local download.
 *
 * Extracted audio always wins over the original source kind. For direct downloads,
 * persisted media metadata is preferred; when it is unknown, only well-known,
 * locally playable filename extensions are inferred.
 */
fun resolveLocalMediaPlaybackType(
    mediaKind: MediaKind?,
    processingMode: DownloadProcessingMode,
    displayName: String?,
    destinationPath: String?,
): LocalMediaPlaybackType? {
    if (processingMode == DownloadProcessingMode.ExtractAudio) {
        return LocalMediaPlaybackType.AUDIO
    }
    if (mediaKind == MediaKind.Image) return null

    when (mediaKind) {
        MediaKind.Audio -> return LocalMediaPlaybackType.AUDIO
        MediaKind.Video -> return LocalMediaPlaybackType.VIDEO
        else -> Unit
    }

    val extensions = sequenceOf(displayName, destinationPath)
        .filterNotNull()
        .mapNotNull(::fileExtension)
        .toList()

    // An explicit unsupported extension should not be offered optimistically to ExoPlayer.
    val firstKnown = extensions.firstOrNull { it in PLAYABLE_AUDIO_EXTENSIONS || it in PLAYABLE_VIDEO_EXTENSIONS }
    return when (firstKnown) {
        in PLAYABLE_AUDIO_EXTENSIONS -> LocalMediaPlaybackType.AUDIO
        in PLAYABLE_VIDEO_EXTENSIONS -> LocalMediaPlaybackType.VIDEO
        else -> null
    }
}

/**
 * Prevents media playback from opening a remote URL or a partial/non-completed download.
 * Destination URIs persisted by the download repository should be content:// or file://.
 */
fun shouldOfferInternalPlayback(
    status: DownloadStatus,
    fileAvailable: Boolean,
    destinationUri: String?,
    playbackType: LocalMediaPlaybackType?,
): Boolean =
    status == DownloadStatus.COMPLETED &&
        fileAvailable &&
        playbackType != null &&
        isLocalMediaUri(destinationUri)

fun isLocalMediaUri(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "content" -> !uri.rawAuthority.isNullOrBlank()
        "file" -> !uri.path.isNullOrBlank()
        else -> false
    }
}

/**
 * Accepts only official YouTube watch/Shorts URLs for the in-app WebView path.
 * Search-result URLs are not direct media streams and must never be fed to ExoPlayer.
 */
fun isTrustedYouTubePlaybackUrl(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.userInfo.isNullOrBlank() || uri.port !in setOf(-1, 443)) return false

    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    val path = uri.path.orEmpty()
    val isYouTubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
    return when {
        isYouTubeHost && path == "/watch" ->
            uri.rawQuery.orEmpty().split('&').any { part ->
                part.substringBefore('=') == "v" && part.substringAfter('=', "").isNotBlank()
            }
        isYouTubeHost && Regex("^/shorts/[^/]+/?$").matches(path) -> true
        host == "youtu.be" && Regex("^/[^/]+/?$").matches(path) -> true
        else -> false
    }
}

private fun fileExtension(value: String): String? {
    val path = value.substringBefore('?').substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('\\\\')
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }
}

private val PLAYABLE_AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr",
)

private val PLAYABLE_VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "webm", "mkv", "mov", "3gp", "mpg", "mpeg", "ts",
)
