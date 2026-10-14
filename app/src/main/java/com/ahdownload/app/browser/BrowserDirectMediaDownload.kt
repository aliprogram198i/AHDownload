package com.ahdownload.app.browser

import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.MediaContainer
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Converts a WebView attachment callback into a narrowly scoped direct-media request.
 *
 * Only HTTP(S) audio/video is accepted. The full signed URL is retained unchanged,
 * while headers and page-cookie context remain transient on the MediaCandidate.
 */
internal object BrowserDirectMediaDownload {
    fun mediaLink(
        url: String,
        mimeType: String?,
        contentDisposition: String?,
        analyzer: LinkAnalyzer,
    ): MediaLink? {
        val exactUrl = url.trim()
        val uri = runCatching { URI(exactUrl) }.getOrNull() ?: return null
        // CandidateValidator currently requires HTTPS for all transfer sources.
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return null
        }

        val analyzed = analyzer.analyze(exactUrl)
        val normalizedMime = safeMimeType(mimeType)
        val mimeKind = kindFromMime(mimeType)
        val mimeAllowsFallback = normalizedMime in setOf(
            "unknown",
            "application/octet-stream",
            "binary/octet-stream",
        )
        // A known non-media response (for example text/html or application/pdf) must
        // not become a media option merely because its URL or filename ends in .mp4.
        if (mimeKind == null && !mimeAllowsFallback) return null
        val extensionKind = kindFromExtension(uri.path.orEmpty())
            ?: filenameFromContentDisposition(contentDisposition)?.let(::kindFromExtension)
        val kind = mimeKind
            ?: analyzed?.takeIf { it.platform == MediaPlatform.DirectMedia }?.kind
            ?: extensionKind
            ?: return null

        if (kind != MediaKind.Video && kind != MediaKind.Audio) return null
        // If MIME type or attachment filename identifies an actual media payload,
        // do not re-route the CDN URL through a social-page resolver.
        return if (
            mimeKind == null &&
            extensionKind == null &&
            analyzed?.platform == MediaPlatform.DirectMedia &&
            analyzed.kind == kind
        ) {
            analyzed
        } else {
            MediaLink(
                originalUrl = exactUrl,
                normalizedUrl = exactUrl,
                platform = MediaPlatform.DirectMedia,
                kind = kind,
            )
        }
    }

    fun requestHeaders(referer: String?, userAgent: String?): Map<String, String> = buildMap {
        referer?.trim()?.let(::safeRefererHeaderValue)?.let { put("Referer", it) }
        userAgent?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 1024 }
            ?.takeIf(::isHeaderSafe)
            ?.let { put("User-Agent", it) }
    }

    /** Return the current page host only; HomeResolver independently enforces same-host cookies. */
    fun sessionCookieHost(pageUrl: String?): String? {
        val uri = pageUrl?.let { runCatching { URI(it.trim()) }.getOrNull() } ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        return uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    fun containerFor(url: String, mimeType: String?, contentDisposition: String?): MediaContainer? {
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val fromMime = when (mime) {
            "video/mp4", "application/mp4", "video/x-m4v" -> MediaContainer.Mp4
            "video/webm" -> MediaContainer.Webm
            "video/quicktime" -> MediaContainer.Mov
            "video/x-matroska" -> MediaContainer.Mkv
            "audio/mpeg", "audio/mp3", "audio/x-mp3" -> MediaContainer.Mp3
            "audio/mp4", "audio/x-m4a" -> MediaContainer.M4a
            "audio/aac", "audio/x-aac" -> MediaContainer.Aac
            "audio/ogg", "application/ogg" -> MediaContainer.Ogg
            "audio/flac", "audio/x-flac" -> MediaContainer.Flac
            "audio/wav", "audio/x-wav", "audio/wave" -> MediaContainer.Wav
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml" -> MediaContainer.Mkv
            else -> null
        }
        if (fromMime != null) return fromMime
        val extension = extensionOf(url) ?: filenameFromContentDisposition(contentDisposition)?.let(::extensionOf)
        return when (extension) {
            "mp4", "m4v" -> MediaContainer.Mp4
            "webm" -> MediaContainer.Webm
            "mkv", "m3u8", "mpd" -> MediaContainer.Mkv
            "mov" -> MediaContainer.Mov
            "m4a" -> MediaContainer.M4a
            "mp3" -> MediaContainer.Mp3
            "aac" -> MediaContainer.Aac
            "ogg", "ogv" -> MediaContainer.Ogg
            "flac" -> MediaContainer.Flac
            "wav" -> MediaContainer.Wav
            "3gp" -> MediaContainer.ThreeGp
            "avi" -> MediaContainer.Avi
            else -> null
        }
    }

    fun displayName(url: String, contentDisposition: String?): String? {
        val fromHeader = filenameFromContentDisposition(contentDisposition)
        val fromUrl = runCatching { URI(url).path.orEmpty().substringAfterLast('/') }.getOrNull()
        return sanitizeFileName(fromHeader ?: fromUrl.orEmpty())
    }

    fun safeMimeType(mimeType: String?): String =
        mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) }
            ?: "unknown"

    private fun kindFromMime(mimeType: String?): MediaKind? {
        val mime = safeMimeType(mimeType)
        return when {
            mime.startsWith("video/") -> MediaKind.Video
            mime.startsWith("audio/") -> MediaKind.Audio
            mime in setOf(
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "application/mpegurl",
                "audio/mpegurl",
                "application/dash+xml",
            ) -> MediaKind.Video
            else -> null
        }
    }

    private fun safeRefererHeaderValue(value: String): String? {
        if (!isHttpUrl(value) || !isHeaderSafe(value)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port >= 0) ":" + uri.port.toString() else ""
        // Strip credentials, query, and fragment from Referer to avoid forwarding
        // page tokens or user-info to the media host.
        return "$scheme://$host$port" + uri.rawPath.orEmpty().take(2048)
    }

    private fun kindFromExtension(value: String): MediaKind? = when (extensionOf(value)) {
        "mp4", "m4v", "webm", "mov", "mkv", "ogv", "3gp", "avi", "m3u8", "mpd" -> MediaKind.Video
        "mp3", "m4a", "aac", "ogg", "flac", "wav" -> MediaKind.Audio
        else -> null
    }

    private fun extensionOf(value: String): String? {
        val path = runCatching { URI(value).path.orEmpty() }.getOrDefault(value.substringBefore('?').substringBefore('#'))
        return path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
    }

    private fun filenameFromContentDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val encoded = Regex("""(?i)filename\*\s*=\s*(?:UTF-8'')?([^;]+)""")
            .find(value)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
        val plain = Regex("""(?i)filename\s*=\s*(?:"([^"]+)"|([^;]+))""")
            .find(value)?.let { it.groupValues.getOrNull(1)?.ifBlank { null } ?: it.groupValues.getOrNull(2) }
            ?.trim()?.trim('"')
        val raw = encoded ?: plain ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)
    }

    private fun sanitizeFileName(value: String): String? =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .take(120)
            .takeIf { it.isNotBlank() && it != "." && it != ".." }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun isHeaderSafe(value: String): Boolean = value.none { it.code < 0x20 || it.code == 0x7f }
}
