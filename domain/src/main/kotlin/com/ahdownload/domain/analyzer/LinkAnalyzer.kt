package com.ahdownload.domain.analyzer

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private val ABSOLUTE_HTTP_URL_PATTERN =
    Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private val EXPLICIT_SCHEME_PATTERN =
    Regex("""[A-Za-z][A-Za-z0-9+.-]*://""", RegexOption.IGNORE_CASE)

private val SCHEMELESS_URL_PATTERN = Regex(
    """(?<![A-Za-z0-9@])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}(?::\d{1,5})?(?:[/?#][^\s<>"']*)?""",
    RegexOption.IGNORE_CASE,
)

class LinkAnalyzer {
    fun analyze(rawUrl: String): MediaLink? {
        val extracted = extractHttpUrl(rawUrl) ?: return null
        val normalized = normalizeTikTokMobileVideoUrl(unwrapRedirect(extracted))
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null

        val host = normalizeHost(uri.host) ?: return null
        val kind = if (isSnapchatSpotlight(uri)) MediaKind.Video else detectMediaKind(uri)

        val platform = when {
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                MediaPlatform.YouTube
            host == "instagram.com" || host.endsWith(".instagram.com") || host == "l.instagram.com" ->
                MediaPlatform.Instagram
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" ->
                MediaPlatform.Facebook
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                MediaPlatform.TikTok
            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") || host == "t.co" ->
                MediaPlatform.X
            host == "snapchat.com" || host.endsWith(".snapchat.com") ->
                MediaPlatform.Snapchat
            host == "pinterest.com" || host.endsWith(".pinterest.com") || host == "pin.it" ->
                MediaPlatform.Pinterest
            host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it" ->
                MediaPlatform.Reddit
            host == "twitch.tv" || host.endsWith(".twitch.tv") ->
                MediaPlatform.Twitch
            host == "vimeo.com" || host.endsWith(".vimeo.com") ->
                MediaPlatform.Vimeo
            kind != MediaKind.Unknown -> MediaPlatform.DirectMedia
            else -> MediaPlatform.Web
        }

        // Keep social-platform resolver routes HTTPS-only. HTTP remains available for
        // regular pages and directly linked media, matching the in-app browser policy.
        if (scheme == "http" && platform !in setOf(MediaPlatform.Web, MediaPlatform.DirectMedia)) return null

        return MediaLink(
            originalUrl = rawUrl,
            normalizedUrl = normalized,
            platform = platform,
            kind = kind,
        )
    }

    private fun extractHttpUrl(raw: String): String? {
        val cleaned = raw.trim().replace("&amp;", "&")
        if (cleaned.isBlank()) return null

        val absoluteMatch = ABSOLUTE_HTTP_URL_PATTERN.find(cleaned)
        val containsExplicitScheme = EXPLICIT_SCHEME_PATTERN.containsMatchIn(cleaned)
        val bareMatch = if (absoluteMatch == null && !containsExplicitScheme) {
            SCHEMELESS_URL_PATTERN.find(cleaned)
        } else {
            null
        }

        val extracted = (absoluteMatch?.value ?: bareMatch?.value ?: cleaned)
            .trim()
            .trimEnd('.', ',', ';', ')', ']', '}', '!', '،', '؛', '…')
        if (extracted.isBlank()) return null

        // Shared text and clipboard contents sometimes omit the scheme. Infer HTTPS only
        // when a hostname-shaped URL token was found; never rewrite explicit ftp:// etc.
        return if (bareMatch != null) "https://$extracted" else extracted
    }

    private fun unwrapRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = normalizeHost(uri.host).orEmpty()
        if (host == "google.com" || host.endsWith(".google.com") ||
            host == "facebook.com" || host.endsWith(".facebook.com")
        ) {
            val query = parseQuery(uri.rawQuery)
            // parseQuery already decodes the outer query value exactly once. Decoding
            // it again corrupts valid percent-escaped data and plus signs in the target URL.
            val target = query["url"] ?: query["u"] ?: query["q"]
            if (!target.isNullOrBlank()) {
                val targetUri = runCatching { URI(target) }.getOrNull()
                val targetScheme = targetUri?.scheme?.lowercase(Locale.ROOT)
                if (targetUri?.host != null && (targetScheme == "http" || targetScheme == "https")) {
                    return target
                }
            }
        }
        return url
    }

    /**
     * The mobile TikTok hostname can return 404 for public /@user/video/id pages.
     * Normalize only canonical video URLs to the primary host; short links and other route
     * families keep their original host/path so their own redirect and routing logic is preserved.
     */
    private fun normalizeTikTokMobileVideoUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (!uri.host.equals("m.tiktok.com", ignoreCase = true)) return url

        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val videoIndex = segments.indexOfLast { it.equals("video", ignoreCase = true) }
        val videoId = segments.getOrNull(videoIndex + 1)
        if (videoIndex < 0 || videoId?.matches(Regex("""\d{6,30}""")) != true) return url

        return Regex("""(?i)^(https?://)m\.tiktok\.com(?=[:/?#]|$)""")
            .replaceFirst(url) { match -> match.groupValues[1] + "www.tiktok.com" }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) null
                else runCatching {
                    URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.toString()) to
                        URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.toString())
                }.getOrNull()
            }
            .toMap()

    private fun normalizeHost(host: String?): String? =
        host?.trim('.')?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.takeIf { it.isNotBlank() }

    private fun isSnapchatSpotlight(uri: URI): Boolean {
        val host = normalizeHost(uri.host).orEmpty()
        val isSnapchatHost = host == "snapchat.com" || host.endsWith(".snapchat.com")
        return isSnapchatHost && uri.path.orEmpty()
            .split('/')
            .any { it.equals("spotlight", ignoreCase = true) }
    }

    private fun detectMediaKind(uri: URI): MediaKind {
        val extension = uri.path
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val query = parseQuery(uri.rawQuery)

        // A syntactically valid MIME type is a strong signal; arbitrary words in query
        // parameters (for example "video-game") are not. HLS/DASH also use application/*.
        val mimeKind = listOf(query["mime"], query["content_type"])
            .mapNotNull(::kindFromMime)
            .firstOrNull()
        if (mimeKind != null) return mimeKind

        val extensionKind = kindFromKnownExtension(extension)
        if (extensionKind != null) return extensionKind

        // Some hosts expose only a format/type/ext hint. Accept known formats and exact
        // category labels, never substring matches against untrusted parameter values.
        return listOf(query["format"], query["type"], query["ext"])
            .filterNotNull()
            .mapNotNull { rawHint ->
                val hint = rawHint.substringBefore(';')
                    .trim()
                    .lowercase(Locale.ROOT)
                    .removePrefix(".")
                kindFromMime(hint) ?: when (hint) {
                    "video" -> MediaKind.Video
                    "audio" -> MediaKind.Audio
                    "image" -> MediaKind.Image
                    else -> kindFromKnownExtension(hint)
                }
            }
            .firstOrNull()
            ?: MediaKind.Unknown
    }

    private fun kindFromMime(rawValue: String?): MediaKind? {
        val mime = rawValue
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return null
        val subtype = mime.substringAfter('/', missingDelimiterValue = "")
        val isValidMediaMime = subtype.matches(Regex("[a-z0-9][a-z0-9.+_-]*"))
        if (!isValidMediaMime) return null

        return when {
            mime in HLS_DASH_MIME_TYPES -> MediaKind.Video
            mime.startsWith("video/") -> MediaKind.Video
            mime.startsWith("audio/") -> MediaKind.Audio
            mime.startsWith("image/") -> MediaKind.Image
            else -> null
        }
    }

    private fun kindFromKnownExtension(extension: String): MediaKind? = when (extension) {
        in VIDEO_EXTENSIONS -> MediaKind.Video
        in AUDIO_EXTENSIONS -> MediaKind.Audio
        in IMAGE_EXTENSIONS -> MediaKind.Image
        else -> null
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mov", "mkv", "3gp", "avi", "ogv", "m3u8", "mpd",
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        val HLS_DASH_MIME_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/dash+xml",
        )
    }
}
