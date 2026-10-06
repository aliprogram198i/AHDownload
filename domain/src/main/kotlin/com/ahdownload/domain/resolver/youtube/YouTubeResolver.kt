package com.ahdownload.domain.resolver.youtube

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
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
    private val playerClient = YouTubePlayerClient(httpClient, logger)

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
            if (isBotChallenge(html)) {
                lastFailure = ResolverResult.Failure(
                    FailureCode.ResolverUnavailable,
                    YOUTUBE_BOT_MESSAGE,
                )
                logger.log(
                    DiagnosticLevel.WARNING,
                    type = "youtube.bot_challenge_detected",
                    reason = YOUTUBE_BOT_MESSAGE,
                    operation = "youtube.resolve",
                    context = diagnosticContext(videoId, request.operationId),
                    throwable = null,
                )
            }
            val direct = parser.parse(html)
            if (direct is ResolverResult.Success) return filterKind(enrichWithSessionIfNeeded(direct, request), request)
            lastFailure = direct as? ResolverResult.Failure
            logPlayerFailure(videoId, direct, "page", request.operationId)
            val apiResponse = runCatching { playerClient.fetchPlayerResponse(html, request.link.normalizedUrl, operationId = request.operationId) }.getOrNull()
            if (apiResponse != null) {
                val apiResult = parser.parsePlayerResponse(apiResponse)
                if (apiResult is ResolverResult.Success) return filterKind(enrichWithSessionIfNeeded(apiResult, request), request)
                lastFailure = apiResult as? ResolverResult.Failure ?: lastFailure
                logPlayerFailure(videoId, apiResult, "youtubei_player", request.operationId)
            }

            // The embedded client is an independent fallback. It must not depend on
            // the primary Player API returning a response; otherwise a network/policy
            // failure on the primary call prevents the only PO-token-light fallback.
            val embeddedResponse = runCatching {
                playerClient.fetchEmbeddedPlayerResponse(
                    html = html,
                    videoUrl = request.link.normalizedUrl,
                    operationId = request.operationId,
                )
            }.getOrNull()
            if (embeddedResponse != null) {
                val embeddedResult = parser.parsePlayerResponse(embeddedResponse)
                if (embeddedResult is ResolverResult.Success) {
                    logger.log(
                        DiagnosticLevel.INFO,
                        type = "youtube.embedded_fallback_selected",
                        reason = "web_embedded_player",
                        operation = "youtube.resolve",
                        context = diagnosticContext(videoId, request.operationId) + mapOf(
                            "candidate_count" to embeddedResult.candidates.size.toString(),
                        ),
                        throwable = null,
                    )
                    return filterKind(enrichWithSessionIfNeeded(embeddedResult, request), request)
                }
                lastFailure = embeddedResult as? ResolverResult.Failure ?: lastFailure
                logPlayerFailure(videoId, embeddedResult, "web_embedded_player", request.operationId)
            }
        } catch (error: Exception) {
            val challenge = error.message?.takeIf(::isBotChallenge)
            lastFailure = ResolverResult.Failure(
                FailureCode.ResolverUnavailable,
                challenge ?: "فشل اتصال YouTube: " + (error.message ?: error::class.simpleName.orEmpty()),
            )
            logger.log(
                DiagnosticLevel.WARNING,
                type = if (challenge != null) "youtube.bot_challenge_detected" else "youtube.primary_failed",
                reason = lastFailure.message ?: "primary_failed",
                operation = "youtube.resolve",
                context = diagnosticContext(videoId, request.operationId),
                throwable = error,
            )
        }

        val provider = sessionProvider ?: return failure(
            lastFailure?.code ?: FailureCode.ResolverUnavailable,
            lastFailure?.message ?: "تعذر استخراج وسائط YouTube.",
            context = diagnosticContext(videoId, request.operationId),
        )

        val snapshot = runCatching { provider.snapshot(request.link.normalizedUrl) }.getOrElse { error ->
            logger.log(
                DiagnosticLevel.WARNING,
                type = "youtube.session_unavailable",
                reason = error.message ?: error::class.simpleName.orEmpty(),
                operation = "youtube.resolve",
                context = diagnosticContext(videoId, request.operationId),
                throwable = error,
            )
            null
        } ?: return failure(
            lastFailure?.code ?: FailureCode.ResolverUnavailable,
            lastFailure?.message ?: "تعذر استخراج وسائط YouTube.",
            context = diagnosticContext(videoId, request.operationId),
        )

        logger.log(
            if (snapshot.authenticated) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            type = "youtube.session_snapshot",
            reason = if (snapshot.authenticated) "authenticated_session" else "session_not_authenticated",
            operation = "youtube.resolve",
            context = diagnosticContext(videoId, request.operationId) + mapOf(
                "cookies_obtained" to (!snapshot.cookies.isNullOrBlank()).toString(),
                "player_response_obtained" to (!snapshot.playerResponse.isNullOrBlank()).toString(),
                "video_candidates" to snapshot.videoUrls.size.toString(),
                "audio_candidates" to snapshot.audioUrls.size.toString(),
                "browser_media_observed" to snapshot.browserMediaObservedCount.toString(),
                "browser_request_headers_captured" to snapshot.browserRequestHeaders.size.toString(),
                "browser_po_token_observed" to snapshot.browserPoTokenObserved.toString(),
            ),
            throwable = null,
        )

        logger.log(
            if (snapshot.browserPoTokenObserved) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            type = "youtube.gvs_strategy",
            reason = when {
                snapshot.browserPoTokenObserved -> "browser_gvs_po_token_observed"
                snapshot.browserMediaObservedCount > 0 -> "browser_gvs_media_observed_without_po_token"
                else -> "no_browser_gvs_media_observed"
            },
            operation = "youtube.resolve",
            context = diagnosticContext(videoId, request.operationId) + mapOf(
                "browser_media_observed" to snapshot.browserMediaObservedCount.toString(),
                "browser_request_headers_captured" to snapshot.browserRequestHeaders.size.toString(),
                "po_token_observed" to snapshot.browserPoTokenObserved.toString(),
                "cookies_obtained" to (!snapshot.cookies.isNullOrBlank()).toString(),
                "authenticated" to snapshot.authenticated.toString(),
            ),
            throwable = null,
        )

        if (snapshot.browserMediaObservedCount == 0) {
            logger.log(
                DiagnosticLevel.WARNING,
                type = "youtube.browser_media_capture_empty",
                reason = "no_googlevideo_media_request_observed",
                operation = "youtube.resolve",
                context = diagnosticContext(videoId, request.operationId) + mapOf(
                    "video_candidates" to snapshot.videoUrls.size.toString(),
                    "audio_candidates" to snapshot.audioUrls.size.toString(),
                    "player_response_obtained" to (!snapshot.playerResponse.isNullOrBlank()).toString(),
                    "browser_po_token_observed" to snapshot.browserPoTokenObserved.toString(),
                ),
                throwable = null,
            )
        }

        snapshot.playerResponse?.takeIf { it.isNotBlank() }?.let { response ->
            logger.log(
                DiagnosticLevel.INFO,
                type = "youtube.webview_player_response",
                reason = "player_response_captured",
                operation = "youtube.resolve",
                context = diagnosticContext(videoId, request.operationId),
                throwable = null,
            )
            val webResult = parser.parsePlayerResponse(response)
            if (webResult is ResolverResult.Success) {
                val sessionResult = withSessionHeaders(webResult, snapshot)
                val browserCandidates = sessionCandidates(snapshot)
                val browserUrls = (snapshot.videoUrls + snapshot.audioUrls).toSet()
                val alignedCount = sessionResult.candidates.count { it.sourceUrl in browserUrls }
                if (alignedCount > 0) {
                    logger.log(
                        DiagnosticLevel.INFO,
                        type = "youtube.webview_source_selected",
                        reason = "aligned_browser_media_source",
                        operation = "youtube.resolve",
                        context = diagnosticContext(videoId, request.operationId) + mapOf(
                            "aligned_candidates" to alignedCount.toString(),
                            "browser_candidates" to browserCandidates.size.toString(),
                        ),
                        throwable = null,
                    )
                    return filterKind(sessionResult, request)
                }
                if (browserCandidates.isNotEmpty()) {
                    logger.log(
                        DiagnosticLevel.INFO,
                        type = "youtube.webview_source_selected",
                        reason = "browser_media_fallback",
                        operation = "youtube.resolve",
                        context = diagnosticContext(videoId, request.operationId) + mapOf(
                            "browser_candidates" to browserCandidates.size.toString(),
                            "player_candidates" to sessionResult.candidates.size.toString(),
                        ),
                        throwable = null,
                    )
                    return filterKind(ResolverResult.Success(
                        title = webResult.title,
                        thumbnailUrl = webResult.thumbnailUrl,
                        durationMs = webResult.durationMs,
                        candidates = browserCandidates,
                    ), request)
                }
                return filterKind(sessionResult, request)
            }
            lastFailure = webResult as? ResolverResult.Failure ?: lastFailure
            logPlayerFailure(videoId, webResult, "webview_player_response", request.operationId)
        }

        val headers = buildMap {
            snapshot.cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            put("Referer", "https://www.youtube.com/")
        }
        if (headers.containsKey("Cookie")) {
            val sessionHtml = runCatching { httpClient.get(request.link.normalizedUrl, headers) }.getOrNull()
            if (sessionHtml != null) {
                val direct = parser.parse(sessionHtml)
                if (direct is ResolverResult.Success) return filterKind(enrichWithSessionIfNeeded(direct, request), request)
                val api = runCatching {
                    playerClient.fetchPlayerResponse(sessionHtml, request.link.normalizedUrl, headers, request.operationId)
                }.getOrNull()
                if (api != null) {
                    val result = parser.parsePlayerResponse(api)
                    if (result is ResolverResult.Success) return filterKind(withSessionHeaders(result, snapshot), request)
                    lastFailure = result as? ResolverResult.Failure ?: lastFailure
                    logPlayerFailure(videoId, result, "youtubei_player_session", request.operationId)
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
                context = diagnosticContext(videoId, request.operationId) + mapOf("candidates" to webCandidates.size.toString()),
                throwable = null,
            )
            return filterKind(ResolverResult.Success(null, null, null, webCandidates), request)
        }

        return failure(
            lastFailure?.code ?: FailureCode.ResolverUnavailable,
            lastFailure?.message ?: "تعذر استخراج وسائط YouTube.",
            context = diagnosticContext(videoId, request.operationId),
        )
    }

    private fun logPlayerFailure(videoId: String, result: ResolverResult, fallback: String, operationId: String?) {
        if (result is ResolverResult.Failure) {
            logger.log(
                DiagnosticLevel.WARNING,
                type = "youtube_player_no_candidates",
                reason = result.message ?: result.code.name,
                operation = "youtube.resolve",
                context = diagnosticContext(videoId, operationId) + mapOf("fallback" to fallback),
                throwable = null,
            )
        }
    }

    private suspend fun enrichWithSessionIfNeeded(
        result: ResolverResult.Success,
        request: ResolverRequest,
    ): ResolverResult.Success {
        if (result.candidates.isEmpty() || result.candidates.none { isYouTubeMediaHost(it.sourceUrl) }) return result
        if (result.candidates.any { it.requestHeaders.isNotEmpty() }) return result
        val provider = sessionProvider ?: return result
        val snapshot = runCatching { provider.snapshot(request.link.normalizedUrl) }.getOrNull() ?: return result
        logger.log(
            DiagnosticLevel.INFO,
            type = "youtube.session_context_attached",
            reason = "session_headers_attached_to_media_candidates",
            operation = "youtube.resolve",
            context = diagnosticContext(extractVideoId(request.link.normalizedUrl).orEmpty(), request.operationId) + mapOf(
                "candidate_count" to result.candidates.size.toString(),
                "cookies_available" to (!snapshot.cookies.isNullOrBlank()).toString(),
            ),
            throwable = null,
        )
        return withSessionHeaders(result, snapshot)
    }

    private fun isYouTubeMediaHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    private fun withSessionHeaders(
        result: ResolverResult.Success,
        snapshot: YouTubeSessionSnapshot,
    ): ResolverResult.Success {
        val headers = sessionHeaders(snapshot)
        val browserUrlsByItag = (snapshot.videoUrls + snapshot.audioUrls)
            .mapNotNull { url -> extractItag(url)?.let { it to url } }
            .toMap()

        var replaced = 0
        var poTokenAttached = 0
        val candidates = result.candidates.map { candidate ->
            val browserUrl = browserUrlsByItag[candidate.id]
            val tokenizedUrl = if (browserUrl == null) appendPoToken(candidate.sourceUrl, snapshot.browserPoToken) else candidate.sourceUrl
            val effectiveUrl = browserUrl ?: tokenizedUrl
            val browserHeaders = snapshot.browserRequestHeaders[effectiveUrl].orEmpty()
            if (browserUrl != null && browserUrl != candidate.sourceUrl) {
                replaced++
                candidate.copy(
                    sourceUrl = browserUrl,
                    requestHeaders = candidate.requestHeaders + headers + browserHeaders,
                )
            } else {
                if (effectiveUrl != candidate.sourceUrl) poTokenAttached++
                candidate.copy(
                    sourceUrl = effectiveUrl,
                    requestHeaders = candidate.requestHeaders + headers + browserHeaders,
                )
            }
        }

        if (poTokenAttached > 0) {
            logger.log(
                DiagnosticLevel.INFO,
                type = "youtube.po_token_attached",
                reason = "browser_gvs_po_token_reused_for_same_session",
                operation = "youtube.resolve",
                context = mapOf("candidate_count" to poTokenAttached.toString()),
                throwable = null,
            )
        }

        if (replaced > 0) {
            logger.log(
                DiagnosticLevel.INFO,
                type = "youtube.browser_media_url_aligned",
                reason = "replaced_player_url_with_webview_media_url",
                operation = "youtube.resolve",
                context = mapOf("replaced_candidates" to replaced.toString()),
                throwable = null,
            )
        }

        return result.copy(candidates = candidates)
    }

    private fun extractItag(url: String): String? =
        runCatching { URI(url).rawQuery.orEmpty().split('&') }
            .getOrDefault(emptyList())
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2 && pieces[0] == "itag") pieces[1] else null
            }
            .firstOrNull()

    private fun sessionHeaders(snapshot: YouTubeSessionSnapshot): Map<String, String> = buildMap {
        snapshot.cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        snapshot.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        put("Referer", "https://www.youtube.com/")
        put("Origin", "https://www.youtube.com")
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
                requestHeaders = sessionHeaders(snapshot) + snapshot.browserRequestHeaders[url].orEmpty(),
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
                    hasVideo = false,
                    hasAudio = true,
                ),
                requestHeaders = sessionHeaders(snapshot) + snapshot.browserRequestHeaders[url].orEmpty(),
            )
        }
        return videos + audio
    }

    private fun appendPoToken(url: String, token: String?): String {
        val value = token?.trim().orEmpty()
        if (value.isEmpty() || !isYouTubeMediaHost(url)) return url
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        if (query.split('&').any { it.substringBefore('=').equals("pot", ignoreCase = true) }) return url
        return url + if (query.isEmpty()) "?pot=$value" else "&pot=$value"
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

    private fun diagnosticContext(videoId: String, operationId: String?): Map<String, String> = buildMap {
        put("video_id", videoId)
        operationId?.takeIf { it.isNotBlank() }?.let { put("operation_id", it) }
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

    private fun isBotChallenge(text: String): Boolean =
        BOT_CHALLENGE_MARKERS.any { text.contains(it, ignoreCase = true) }

    private companion object {
        const val YOUTUBE_BOT_MESSAGE =
            "YouTube يطلب التحقق من أنك لست روبوتًا. افتح YouTube لتحديث الجلسة ثم أعد المحاولة."

        val BOT_CHALLENGE_MARKERS = listOf(
            "Sign in to confirm you’re not a bot",
            "Sign in to confirm you're not a bot",
            "confirm you're not a bot",
            "confirm you’re not a bot",
        )
    }

}
