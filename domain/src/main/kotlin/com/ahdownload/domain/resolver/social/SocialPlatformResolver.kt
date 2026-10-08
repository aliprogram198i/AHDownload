package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.*
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import com.ahdownload.domain.resolver.browser.WebPageMediaParser
import java.net.URI
import java.net.URLDecoder

class SocialPlatformResolver(
    private val provider: BrowserMediaSessionProvider,
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) : PlatformAdapter {

    private val supported = setOf(
        MediaPlatform.Instagram,
        MediaPlatform.Facebook,
        MediaPlatform.TikTok,
        MediaPlatform.X,
        MediaPlatform.Snapchat,
        MediaPlatform.Pinterest,
        MediaPlatform.Reddit,
        MediaPlatform.Twitch,
        MediaPlatform.Vimeo,
    )

    override val capability = ResolverCapability(
        platform = MediaPlatform.SocialWeb,
        supportedKinds = setOf(MediaKind.Video, MediaKind.Audio, MediaKind.Image),
    )

    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        val platform = request.link.platform
        if (platform !in supported) {
            return ResolverResult.Failure(
                FailureCode.UnsupportedPlatform,
                "هذه المنصة غير مدعومة في محرك الويب الحالي.",
            )
        }

        return try {
            val session = provider.snapshot(request.link.normalizedUrl, platform)
            val pageUrl = session.finalUrl ?: session.pageUrl
            val fallback = runCatching { OkHttpTextClient().get(pageUrl) }
                .getOrNull()
                ?.let { WebPageMediaParser.parse(it, pageUrl) }

            val title = session.title ?: fallback?.title
            val thumbnail = session.thumbnailUrl ?: fallback?.thumbnailUrl
            val duration = session.durationMs ?: fallback?.durationMs
            val urls = (session.mediaUrls + fallback?.mediaUrls.orEmpty())
                .distinct()
                .take(64)

            val candidates = urls.mapIndexedNotNull { index, url ->
                inferCandidate(platform, url, index, session.requestHeadersByUrl[url])
            }.distinctBy {
                listOf(
                    it.format.kind,
                    it.format.container,
                    it.format.height ?: 0,
                    it.format.bitrateKbps ?: 0,
                    it.sourceUrl,
                )
            }.sortedWith(
                compareByDescending<MediaCandidate> { it.format.kind == MediaKind.Video }
                    .thenByDescending { it.format.height ?: 0 }
                    .thenByDescending { it.format.bitrateKbps ?: 0 },
            )

            logger.log(
                if (candidates.isNotEmpty()) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                "SOCIAL_RESOLUTION_RESULT",
                if (candidates.isNotEmpty()) "تم العثور على مصادر وسائط عامة" else "لم يتم العثور على مصدر وسائط مباشر",
                "social.resolve",
                mapOf(
                    "platform" to platform.name,
                    "candidate_count" to candidates.size.toString(),
                    "title_present" to (!title.isNullOrBlank()).toString(),
                ),
                null,
            )

            if (candidates.isEmpty()) {
                ResolverResult.Failure(
                    FailureCode.NoCandidates,
                    "تعذر اكتشاف مصدر وسائط مباشر من الصفحة العامة حاليًا.",
                )
            } else {
                ResolverResult.Success(
                    title = title,
                    thumbnailUrl = thumbnail,
                    durationMs = duration,
                    candidates = candidates,
                )
            }
        } catch (error: Throwable) {
            logger.log(
                DiagnosticLevel.ERROR,
                "SOCIAL_BROWSER_SESSION_FAILED",
                error.message ?: error::class.simpleName.orEmpty(),
                "social.resolve",
                mapOf("platform" to platform.name),
                error,
            )
            ResolverResult.Failure(
                FailureCode.ResolverUnavailable,
                "تعذر تحليل الصفحة حاليًا. أعد المحاولة أو افتح التشخيص لمعرفة السبب.",
            )
        }
    }

    private fun inferCandidate(
        platform: MediaPlatform,
        sourceUrl: String,
        index: Int,
        requestHeaders: Map<String, String>?,
    ): MediaCandidate? {
        val queryValues = runCatching {
            URI(sourceUrl).rawQuery.orEmpty()
                .split('&')
                .mapNotNull { part ->
                    if (part.isBlank()) return@mapNotNull null
                    val key = part.substringBefore('=')
                    val rawValue = part.substringAfter('=', "")
                    val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
                    key to value
                }
        }.getOrDefault(emptyList())

        val mediaTypeKeys = setOf(
            "mime",
            "mime_type",
            "mimetype",
            "mimetype",
            "content-type",
            "content_type",
            "contentType",
            "type",
            "media_type",
            "mediaType",
            "format",
            "media_format",
            "video_mime_type",
            "audio_mime_type",
        )

        val mediaValue = queryValues
            .firstOrNull { (key, value) ->
                key in mediaTypeKeys || value.contains("video/", true) || value.contains("audio/", true)
            }
            ?.second
            .orEmpty()
            .lowercase()

        val path = sourceUrl.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()
        val queryExtension = queryValues
            .firstOrNull { (key, _) -> key.equals("ext", true) || key.equals("extension", true) || key.equals("format", true) }
            ?.second
            ?.substringBefore('?')
            ?.lowercase()
            .orEmpty()

        val directMediaHint =
            mediaValue.startsWith("video") ||
                mediaValue.startsWith("audio") ||
                mediaValue.contains("video/") ||
                mediaValue.contains("audio/") ||
                extension in VIDEO_EXTENSIONS ||
                extension in AUDIO_EXTENSIONS ||
                queryExtension in VIDEO_EXTENSIONS ||
                queryExtension in AUDIO_EXTENSIONS

        if (!directMediaHint && isKnownPlayerPage(platform, sourceUrl)) {
            return null
        }

        val kind = when {
            mediaValue.startsWith("video") || mediaValue.contains("video/") -> MediaKind.Video
            mediaValue.startsWith("audio") || mediaValue.contains("audio/") -> MediaKind.Audio
            extension in VIDEO_EXTENSIONS || queryExtension in VIDEO_EXTENSIONS -> MediaKind.Video
            extension in AUDIO_EXTENSIONS || queryExtension in AUDIO_EXTENSIONS -> MediaKind.Audio
            isLikelyVideoPath(sourceUrl) && !isKnownPlayerPage(platform, sourceUrl) -> MediaKind.Video
            else -> return null
        }

        val height = (
            Regex("""(?i)(?:[?&](?:height|h|resolution|quality)=|/)(\\d{3,4})p?""")
                .find(sourceUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: queryValues.firstOrNull { (key, _) ->
                    key.equals("height", true) || key.equals("video_height", true) || key.equals("h", true)
                }?.second?.toIntOrNull()
        )

        val effectiveExtension = extension.ifBlank { queryExtension }
        val container = when {
            effectiveExtension == "mp4" || mediaValue.contains("video/mp4") -> MediaContainer.Mp4
            effectiveExtension == "webm" || mediaValue.contains("video/webm") -> MediaContainer.Webm
            effectiveExtension == "mov" || mediaValue.contains("quicktime") -> MediaContainer.Mov
            effectiveExtension == "mkv" -> MediaContainer.Mkv
            effectiveExtension == "m4a" || mediaValue.contains("audio/mp4") -> MediaContainer.M4a
            effectiveExtension == "mp3" || mediaValue.contains("audio/mpeg") -> MediaContainer.Mp3
            effectiveExtension == "aac" -> MediaContainer.Aac
            effectiveExtension == "ogg" -> MediaContainer.Ogg
            effectiveExtension == "flac" -> MediaContainer.Flac
            effectiveExtension == "wav" -> MediaContainer.Wav
            effectiveExtension == "3gp" -> MediaContainer.ThreeGp
            effectiveExtension == "avi" -> MediaContainer.Avi
            else -> MediaContainer.Unknown
        }

        return MediaCandidate(
            id = "social-" + platform.name.lowercase() + "-" + index + "-" + sourceUrl.hashCode().toUInt().toString(16),
            sourceUrl = sourceUrl,
            format = MediaFormat(
                id = "social-" + kind.name.lowercase() + "-" + container.name.lowercase() + "-" + index,
                kind = kind,
                container = container,
                height = height,
                hasVideo = kind == MediaKind.Video,
                hasAudio = kind == MediaKind.Video || kind == MediaKind.Audio,
            ),
            requestHeaders = requestHeaders.orEmpty().filterKeys(::safeHeader),
            sessionCookieHost = safeHost(sourceUrl),
            sourceContext = com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED,
        )
    }

    private fun isKnownPlayerPage(platform: MediaPlatform, sourceUrl: String): Boolean {
        val lower = sourceUrl.lowercase()
        val host = runCatching { URI(sourceUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        return when (platform) {
            MediaPlatform.Facebook -> lower.contains("/plugins/post.php")
            MediaPlatform.Instagram -> lower.contains("/embed/")
            MediaPlatform.TikTok -> lower.contains("/player/v1/")
            MediaPlatform.Pinterest -> host == "assets.pinterest.com" && lower.contains("/ext/embed")
            MediaPlatform.Twitch -> host == "www.twitch.tv" || host == "twitch.tv" && Regex("/videos/\\d+").containsMatchIn(lower)
            MediaPlatform.Vimeo -> host == "player.vimeo.com" && lower.contains("/video/")
            MediaPlatform.Snapchat -> lower.contains("/spotlight/") && host.endsWith("snapchat.com")
            MediaPlatform.Reddit -> lower.contains("/comments/") && host.endsWith("reddit.com")
            else -> false
        }
    }

    private fun isLikelyVideoPath(sourceUrl: String): Boolean {
        val lower = sourceUrl.lowercase()
        return Regex("""/(?:video|videos|videoplayback|playback|stream|media|download)(?:/|[?]|$)""").containsMatchIn(lower)
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "avi")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav")
    }

    private fun safeHeader(name: String): Boolean = when {
        name.equals("Authorization", true) -> false
        name.equals("Cookie", true) -> true
        name.equals("User-Agent", true) || name.equals("Referer", true) || name.equals("Origin", true) -> true
        name.equals("Accept", true) || name.equals("Accept-Language", true) -> true
        name.startsWith("Sec-Fetch-", true) -> true
        else -> false
    }

    private fun safeHost(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
}
