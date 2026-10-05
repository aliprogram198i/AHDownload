package com.ahdownload.domain.resolver.youtube

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.PlatformAdapter
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import java.net.URI

class YouTubeResolver(
    private val httpClient: HttpTextClient,
    private val parser: YouTubePlayerResponseParser = YouTubePlayerResponseParser(),
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val sessionProvider: YouTubeSessionProvider? = null,
) : PlatformAdapter {
    private val playerClient = YouTubePlayerClient(httpClient)

    override val capability = com.ahdownload.domain.resolver.ResolverCapability(
        platform = MediaPlatform.YouTube,
        supportedKinds = setOf(MediaKind.Video, MediaKind.Audio),
    )

    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        if (request.link.platform != MediaPlatform.YouTube) {
            return ResolverResult.Failure(FailureCode.UnsupportedPlatform, "الرابط ليس YouTube.")
        }

        val videoId = extractVideoId(request.link.normalizedUrl)
            ?: return failure(FailureCode.ResolverUnavailable, "تعذر تحديد معرف فيديو YouTube.")

        var lastFailure: ResolverResult.Failure? = null
        try {
            val html = httpClient.get(request.link.normalizedUrl)
            val direct = parser.parse(html)
            if (direct is ResolverResult.Success) return filterKind(direct, request)
            lastFailure = direct as? ResolverResult.Failure
            logPlayerFailure(videoId, direct, "page")
            val apiResponse = runCatching { playerClient.fetchPlayerResponse(html, request.link.normalizedUrl) }.getOrNull()
            if (apiResponse != null) {
                val apiResult = parser.parsePlayerResponse(apiResponse)
                if (apiResult is ResolverResult.Success) return filterKind(apiResult, request)
                lastFailure = apiResult as? ResolverResult.Failure ?: lastFailure
                logPlayerFailure(videoId, apiResult, "youtubei_player")
            }
        } catch (error: Exception) {
            lastFailure = ResolverResult.Failure(
                FailureCode.ResolverUnavailable,
                "فشل اتصال YouTube: " + (error.message ?: error::class.simpleName.orEmpty()),
            )
            logger.log(
                DiagnosticLevel.WARNING,
                type = "youtube.primary_failed",
                reason = lastFailure?.message ?: "primary_failed",
                operation = "youtube.resolve",
                context = mapOf("video_id" to videoId),
                throwable = error,
            )
        }

        val provider = sessionProvider ?: return failure(
            lastFailure?.code ?: FailureCode.ResolverUnavailable,
            lastFailure?.message ?: "تعذر استخراج وسائط YouTube.",
            context = mapOf("video_id" to videoId),
        )

        val snapshot = runCatching { provider.snapshot(request.link.normalizedUrl) }.getOrElse { error ->
            logger.log(
                DiagnosticLevel.WARNING,
                type = "youtube.session_unavailable",
                reason = error.message ?: error::class.simpleName.orEmpty(),
                operation = "youtube.resolve",
                context = mapOf("video_id" to videoId),
                throwable = error,
            )
            null
        } ?: return failure(
            lastFailure?.code ?: FailureCode.ResolverUnavailable,
            lastFailure?.message ?: "تعذر استخراج وسائط YouTube.",
            context = mapOf("video_id" to videoId),
        )

        logger.log(
            if (snapshot.authenticated) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            type = "youtube.session_snapshot",
            reason = if (snapshot.authenticated) "authenticated_session" else "session_not_authenticated",
            operation = "youtube.resolve",
            context = mapOf(
                "video_id" to videoId,
                "cookies_obtained" to (!snapshot.cookies.isNullOrBlank()).toString(),
                "video_candidates" to snapshot.videoUrls.size.toString(),
                "audio_candidates" to snapshot.audioUrls.size.toString(),
            ),
            throwable = null,
        )

        val headers = buildMap {
            snapshot.cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            put("Referer", "https://www.youtube.com/")
        }
        if (headers.containsKey("Cookie")) {
            val sessionHtml = runCatching { httpClient.get(request.link.normalizedUrl, headers) }.getOrNull()
            if (sessionHtml != null) {
                val direct = parser.parse(sessionHtml)
                if (direct is ResolverResult.Success) return filterKind(direct, request)
                val api = runCatching {
                    playerClient.fetchPlayerResponse(sessionHtml, request.link.normalizedUrl, headers)
                }.getOrNull()
                if (api != null) {
                    val result = parser.parsePlayerResponse(api)
                    if (result is ResolverResult.Success) return filterKind(result, request)
                    lastFailure = result as? ResolverResult.Failure ?: lastFailure
                    logPlayerFailure(videoId, result, "youtubei_player_session")
                }
            }
        }

        val webCandidates = sessionCandidates(snapshot)
        if (webCandidates.isNotEmpty()) {
            logger.log(
                DiagnosticLevel.INFO,
                type = "youtube.webview_candidates",
                reason = "direct_media_candidates",
                operation = "youtube.resolve",
                context = mapOf("video_id" to videoId, "candidates" to webCandidates.size.toString()),
                throwable = null,
            )
            return filterKind(ResolverResult.Success(null, null, null, webCandidates), request)
        }

        if (!snapshot.authenticated && snapshot.cookies.isNullOrBlank()) {
            return failure(
                FailureCode.ResolverUnavailable,
                "YouTube يتطلب جلسة WebView صالحة. افتح YouTube داخل التطبيق وسجّل الدخول ثم أعد المحاولة.",
                context = mapOf("video_id" to videoId, "reason_class" to "AUTH_REQUIRED"),
            )
        }

        return failure(
            lastFailure?.code ?: FailureCode.ResolverUnavailable,
            lastFailure?.message ?: "تعذر استخراج وسائط YouTube.",
            context = mapOf("video_id" to videoId),
        )
    }

    private fun logPlayerFailure(videoId: String, result: ResolverResult, fallback: String) {
        if (result is ResolverResult.Failure) {
            logger.log(
                DiagnosticLevel.WARNING,
                type = "youtube_player_no_candidates",
                reason = result.message ?: result.code.name,
                operation = "youtube.resolve",
                context = mapOf("video_id" to videoId, "fallback" to fallback),
                throwable = null,
            )
        }
    }

    private fun sessionCandidates(snapshot: YouTubeSessionSnapshot): List<MediaCandidate> {
        val videos = snapshot.videoUrls.filter(::isDirectHttpMedia).distinct().mapIndexed { index, url ->
            MediaCandidate(
                id = "webview-video-${index}-${url.hashCode().toUInt().toString(16)}",
                sourceUrl = url,
                format = MediaFormat(
                    id = "webview-video-${index}",
                    kind = MediaKind.Video,
                    container = containerFor(url, MediaKind.Video),
                    hasVideo = true,
                    hasAudio = true,
                ),
            )
        }
        val audio = snapshot.audioUrls.filter(::isDirectHttpMedia).distinct().mapIndexed { index, url ->
            MediaCandidate(
                id = "webview-audio-${index}-${url.hashCode().toUInt().toString(16)}",
                sourceUrl = url,
                format = MediaFormat(
                    id = "webview-audio-${index}",
                    kind = MediaKind.Audio,
                    container = containerFor(url, MediaKind.Audio),
                    hasAudio = true,
                ),
            )
        }
        return videos + audio
    }

    private fun isDirectHttpMedia(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("https://") || lower.startsWith("http://")) &&
            !lower.contains(".m3u8") &&
            !lower.startsWith("blob:")
    }

    private fun containerFor(url: String, kind: MediaKind): MediaContainer {
        val path = url.substringBefore("?").substringBefore("#").lowercase()
        val mime = Regex("""[?&](?:mime|type)=([^&]+)""").find(url)?.groupValues?.getOrNull(1)
        val value = java.net.URLDecoder.decode(mime ?: path.substringAfterLast('.'), "UTF-8").lowercase()
        return when {
            value.contains("mp4") -> if (kind == MediaKind.Audio) MediaContainer.M4a else MediaContainer.Mp4
            value.contains("webm") -> MediaContainer.Webm
            value.contains("m4a") -> MediaContainer.M4a
            value.contains("mp3") -> MediaContainer.Mp3
            value.contains("aac") -> MediaContainer.Aac
            value.contains("ogg") -> MediaContainer.Ogg
            value.contains("mov") -> MediaContainer.Mov
            else -> MediaContainer.Unknown
        }
    }

    private fun filterKind(result: ResolverResult.Success, request: ResolverRequest): ResolverResult {
        val candidates = request.requestedKind?.let { kind ->
            result.candidates.filter { it.format.kind == kind }
        } ?: result.candidates
        return if (candidates.isEmpty()) {
            failure(FailureCode.NoCandidates, "لا توجد صيغة مطابقة لنوع الوسائط المطلوب.")
        } else {
            result.copy(candidates = candidates)
        }
    }

    private fun failure(
        code: FailureCode,
        reason: String,
        error: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    ): ResolverResult.Failure {
        logger.log(
            DiagnosticLevel.ERROR,
            type = code.name,
            reason = reason,
            operation = "youtube.resolve",
            context = context,
            throwable = error,
        )
        return ResolverResult.Failure(code, reason)
    }

    private fun extractVideoId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "youtu.be") return uri.path.trim('/').substringBefore('/').takeIf { it.length >= 6 }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val queryId = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2 && pieces[0] == "v") pieces[1] else null
            }
            if (queryId != null) return queryId
            val segments = uri.path.trim('/').split('/')
            val markerIndex = segments.indexOfFirst { it == "shorts" || it == "embed" || it == "live" }
            if (markerIndex >= 0) return segments.getOrNull(markerIndex + 1)
        }
        return null
    }

    private fun ResolverResult.failureCodeOr(default: FailureCode): FailureCode =
        (this as? ResolverResult.Failure)?.code ?: default

    private fun ResolverResult.failureMessageOr(default: String): String =
        (this as? ResolverResult.Failure)?.message ?: default
}
