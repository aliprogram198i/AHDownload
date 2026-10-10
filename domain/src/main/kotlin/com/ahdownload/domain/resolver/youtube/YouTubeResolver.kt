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

private data class KnownYouTubeFormat(
    val kind: MediaKind,
    val container: MediaContainer,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val bitrateKbps: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hasAudio: Boolean = false,
)

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

    override suspend fun resolve(request: ResolverRequest): ResolverResult =
        resolveInternal(request, forceFallbacks = false)

    /**
     * Re-resolves YouTube through alternate client paths when a selected source fails
     * validation. Refreshing is used for HTTP 403 and raw-media content-type mismatches,
     * so the caller can validate genuinely alternate candidates instead of blindly
     * accepting an unsupported response such as application/vnd.yt-ump.
     */
    suspend fun resolveWithFallbacks(request: ResolverRequest): ResolverResult =
        resolveInternal(request, forceFallbacks = true)

    private suspend fun resolveInternal(
        request: ResolverRequest,
        forceFallbacks: Boolean,
    ): ResolverResult {
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
            if (direct is ResolverResult.Success) {
                val base = if (shouldExpandFormatCatalog(direct, request, forceFallbacks)) {
                    if (forceFallbacks) {
                        logger.log(
                            DiagnosticLevel.INFO,
                            type = "youtube.fallback_clients_forced",
                            reason = "refresh_after_media_validation_failure",
                            operation = "youtube.resolve",
                            context = diagnosticContext(videoId, request.operationId),
                            throwable = null,
                        )
                    }
                    augmentWithAndroidFallback(
                        augmentWithEmbeddedFallback(direct, html, request),
                        html,
                        request,
                    )
                } else {
                    direct
                }
                return filterKind(enrichWithSessionIfNeeded(base, request), request)
            }
            lastFailure = direct as? ResolverResult.Failure
            logPlayerFailure(videoId, direct, "page", request.operationId)
            val apiResponse = runCatching { playerClient.fetchPlayerResponse(html, request.link.normalizedUrl, operationId = request.operationId) }.getOrNull()
            if (apiResponse != null) {
                val apiResult = parser.parsePlayerResponse(apiResponse)
                if (apiResult is ResolverResult.Success) {
                    val base = if (shouldExpandFormatCatalog(apiResult, request, forceFallbacks)) {
                        if (forceFallbacks) {
                            logger.log(
                                DiagnosticLevel.INFO,
                                type = "youtube.fallback_clients_forced",
                                reason = "refresh_after_media_validation_failure",
                                operation = "youtube.resolve",
                                context = diagnosticContext(videoId, request.operationId),
                                throwable = null,
                            )
                        }
                        augmentWithAndroidFallback(
                            augmentWithEmbeddedFallback(apiResult, html, request),
                            html,
                            request,
                        )
                    } else {
                        apiResult
                    }
                    return filterKind(enrichWithSessionIfNeeded(base, request), request)
                }
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
            val webResult = parser.parsePlayerResponse(response, snapshot.videoUrls, snapshot.audioUrls)
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

    /**
     * A single playable candidate is enough to start a download, but it is not
     * enough for the result picker: the user still needs several video qualities
     * and an independent audio source for extraction. Expand sparse catalogs
     * through the embedded and Android clients before presenting the result.
     */
    private fun shouldExpandFormatCatalog(
        result: ResolverResult.Success,
        request: ResolverRequest,
        forceFallbacks: Boolean,
    ): Boolean {
        if (forceFallbacks) return true

        val candidates = result.candidates
        val videoCandidates = candidates.filter {
            it.format.kind == MediaKind.Video && it.format.hasVideo
        }
        val audioCandidates = candidates.filter {
            it.format.kind == MediaKind.Audio && it.format.hasAudio
        }
        val distinctVideoQualities = videoCandidates.mapNotNull {
            it.format.height
        }.distinct().size

        return when (request.requestedKind) {
            MediaKind.Audio -> audioCandidates.size < 2 || videoCandidates.isEmpty()
            MediaKind.Video -> distinctVideoQualities < MIN_VIDEO_QUALITIES || audioCandidates.isEmpty()
            else -> distinctVideoQualities < MIN_VIDEO_QUALITIES ||
                audioCandidates.isEmpty() ||
                videoCandidates.isEmpty()
        }
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

    private suspend fun augmentWithAndroidFallback(
        result: ResolverResult.Success,
        html: String,
        request: ResolverRequest,
    ): ResolverResult.Success {
        if (result.candidates.none { isYouTubeMediaHost(it.sourceUrl) }) return result

        val videoId = extractVideoId(request.link.normalizedUrl).orEmpty()
        val androidResponse = runCatching {
            playerClient.fetchAndroidPlayerResponse(
                html = html,
                videoUrl = request.link.normalizedUrl,
                operationId = request.operationId,
            )
        }.getOrNull() ?: return result
        val androidResult = parser.parsePlayerResponse(androidResponse)
        if (androidResult !is ResolverResult.Success) {
            logPlayerFailure(videoId, androidResult, "android_player", request.operationId)
            return result
        }

        val existingUrls = result.candidates.mapTo(linkedSetOf()) { it.sourceUrl }
        val androidCandidates = androidResult.candidates
            .filter { it.sourceUrl !in existingUrls && isYouTubeMediaHost(it.sourceUrl) }
            .map { candidate ->
                candidate.copy(
                    id = "android-${candidate.id}",
                    format = candidate.format.copy(id = "android-${candidate.format.id}"),
                )
            }
        if (androidCandidates.isEmpty()) return result

        logger.log(
            DiagnosticLevel.INFO,
            type = "youtube.android_fallback_candidates_added",
            reason = "android_player_direct_media_fallback",
            operation = "youtube.resolve",
            context = diagnosticContext(videoId, request.operationId) + mapOf(
                "primary_candidates" to result.candidates.size.toString(),
                "android_candidates" to androidCandidates.size.toString(),
            ),
            throwable = null,
        )
        return result.copy(candidates = result.candidates + androidCandidates)
    }

    private suspend fun augmentWithEmbeddedFallback(
        result: ResolverResult.Success,
        html: String,
        request: ResolverRequest,
    ): ResolverResult.Success {
        if (result.candidates.none { isYouTubeMediaHost(it.sourceUrl) }) return result

        val videoId = extractVideoId(request.link.normalizedUrl).orEmpty()
        val embeddedResponse = runCatching {
            playerClient.fetchEmbeddedPlayerResponse(
                html = html,
                videoUrl = request.link.normalizedUrl,
                operationId = request.operationId,
            )
        }.getOrNull() ?: return result

        val embeddedResult = parser.parsePlayerResponse(embeddedResponse)
        if (embeddedResult !is ResolverResult.Success) {
            logPlayerFailure(videoId, embeddedResult, "web_embedded_player", request.operationId)
            return result
        }

        val existingUrls = result.candidates.mapTo(linkedSetOf()) { it.sourceUrl }
        val embeddedCandidates = embeddedResult.candidates
            .filter { it.sourceUrl !in existingUrls }
            .map { candidate ->
                candidate.copy(
                    id = "embedded-${candidate.id}",
                    format = candidate.format.copy(id = "embedded-${candidate.format.id}"),
                )
            }

        if (embeddedCandidates.isEmpty()) return result

        logger.log(
            DiagnosticLevel.INFO,
            type = "youtube.embedded_candidates_added",
            reason = "web_embedded_player_available",
            operation = "youtube.resolve",
            context = diagnosticContext(videoId, request.operationId) + mapOf(
                "primary_candidates" to result.candidates.size.toString(),
                "embedded_candidates" to embeddedCandidates.size.toString(),
            ),
            throwable = null,
        )
        return result.copy(candidates = result.candidates + embeddedCandidates)
    }

    private suspend fun enrichWithSessionIfNeeded(
        result: ResolverResult.Success,
        request: ResolverRequest,
    ): ResolverResult.Success {
        val youtubeCandidates = result.candidates.filter { isYouTubeMediaHost(it.sourceUrl) }
        if (youtubeCandidates.isEmpty()) return result
        if (youtubeCandidates.none { it.requestHeaders.isEmpty() }) return result
        val provider = sessionProvider ?: return result
        val snapshot = runCatching { provider.snapshot(request.link.normalizedUrl) }.getOrNull() ?: return result
        logger.log(
            if (snapshot.browserPoTokenObserved) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            type = "youtube.gvs_strategy",
            reason = when {
                snapshot.browserPoTokenObserved -> "browser_gvs_po_token_observed"
                snapshot.browserMediaObservedCount > 0 -> "browser_gvs_media_observed_without_po_token"
                else -> "no_browser_gvs_media_observed"
            },
            operation = "youtube.resolve",
            context = diagnosticContext(
                extractVideoId(request.link.normalizedUrl).orEmpty(),
                request.operationId,
            ) + mapOf(
                "browser_media_observed" to snapshot.browserMediaObservedCount.toString(),
                "browser_request_headers_captured" to snapshot.browserRequestHeaders.size.toString(),
                "po_token_observed" to snapshot.browserPoTokenObserved.toString(),
                "cookies_obtained" to (!snapshot.cookies.isNullOrBlank()).toString(),
                "authenticated" to snapshot.authenticated.toString(),
            ),
            throwable = null,
        )

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
        val sessionEnriched = withSessionHeaders(result, snapshot)
        val capturedPlayerCandidates = snapshot.playerResponse
            ?.takeIf { it.isNotBlank() }
            ?.let { parser.parsePlayerResponse(it, snapshot.videoUrls, snapshot.audioUrls) }
            ?.let { it as? ResolverResult.Success }
            ?.let { withSessionHeaders(it, snapshot).candidates }
            .orEmpty()
            .filter {
                it.sourceContext == com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED
            }

        val knownSourceUrls = sessionEnriched.candidates
            .mapTo(mutableSetOf()) { it.sourceUrl }
        val recoveredCipherCandidates = capturedPlayerCandidates.filter { candidate ->
            knownSourceUrls.add(candidate.sourceUrl)
        }

        if (recoveredCipherCandidates.isNotEmpty()) {
            logger.log(
                DiagnosticLevel.INFO,
                type = "youtube.cipher_format_metadata_recovered",
                reason = "player_metadata_matched_to_exact_browser_media_urls",
                operation = "youtube.resolve",
                context = diagnosticContext(
                    extractVideoId(request.link.normalizedUrl).orEmpty(),
                    request.operationId,
                ) + mapOf(
                    "recovered_candidate_count" to recoveredCipherCandidates.size.toString(),
                    "recovered_video_count" to recoveredCipherCandidates.count {
                        it.format.kind == MediaKind.Video
                    }.toString(),
                    "recovered_audio_count" to recoveredCipherCandidates.count {
                        it.format.kind == MediaKind.Audio
                    }.toString(),
                ),
                throwable = null,
            )
            return sessionEnriched.copy(
                candidates = sessionEnriched.candidates + recoveredCipherCandidates,
            )
        }

        return sessionEnriched
    }

    private fun isYouTubeMediaHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    private fun withSessionHeaders(
        result: ResolverResult.Success,
        snapshot: YouTubeSessionSnapshot,
    ): ResolverResult.Success {
        val sessionHeaders = sessionHeaders(snapshot)
        val browserUrlsByItag = (snapshot.videoUrls + snapshot.audioUrls)
            .mapNotNull { url -> extractItag(url)?.let { it to url } }
            .toMap()

        var exactBrowserSources = 0
        val candidates = result.candidates.map { candidate ->
            val candidateItag = extractItag(candidate.sourceUrl)
            val browserUrl = candidateItag?.let(browserUrlsByItag::get)

            if (browserUrl != null) {
                val browserHeaders = snapshot.browserRequestHeaders[browserUrl].orEmpty()
                exactBrowserSources++
                candidate.copy(
                    // Browser-observed GVS URLs are session-bound. Preserve the exact
                    // URL captured by WebView; never copy a PO token from another URL.
                    sourceUrl = browserUrl,
                    requestHeaders = candidate.requestHeaders + sessionHeaders + browserHeaders,
                    sourceContext = com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED,
                )
            } else {
                candidate.copy(
                    requestHeaders = candidate.requestHeaders + sessionHeaders,
                )
            }
        }

        if (exactBrowserSources > 0) {
            logger.log(
                DiagnosticLevel.INFO,
                type = "youtube.browser_media_context_attached",
                reason = "exact_browser_gvs_url_and_context_preserved",
                operation = "youtube.resolve",
                context = mapOf(
                    "exact_browser_sources" to exactBrowserSources.toString(),
                    "browser_context_entries" to snapshot.browserRequestHeaders.size.toString(),
                ),
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

    private fun isKnownMuxedItag(itag: String?): Boolean = itag in KNOWN_MUXED_ITAGS

    private fun sessionHeaders(snapshot: YouTubeSessionSnapshot): Map<String, String> = buildMap {
        snapshot.cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        snapshot.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        put("Referer", "https://www.youtube.com/")
        put("Origin", "https://www.youtube.com")
    }

    private fun sessionCandidates(snapshot: YouTubeSessionSnapshot): List<MediaCandidate> {
        val sessionHeaders = sessionHeaders(snapshot)

        // WebView can observe a usable URL even when the Player Response is
        // unavailable. Recover basic, stable format metadata from YouTube's
        // public itag identifiers, and classify audio/video from MIME first.
        // The URL itself remains the exact browser-observed URL.
        val observedUrls = (
            snapshot.videoUrls.map { it to MediaKind.Video } +
                snapshot.audioUrls.map { it to MediaKind.Audio }
            )
            .filter { (url, _) -> isDirectHttpMedia(url) }
            .distinctBy { (url, _) -> url }

        return observedUrls.mapIndexedNotNull { index, (url, poolKind) ->
            val itag = extractItag(url)
            val knownFormat = KNOWN_YOUTUBE_FORMATS[itag]
            val kind = mediaKindFromUrl(url) ?: knownFormat?.kind ?: poolKind
            if (kind != MediaKind.Video && kind != MediaKind.Audio) {
                return@mapIndexedNotNull null
            }
            val metadata = knownFormat?.takeIf { it.kind == kind }
            val observedContainer = containerFor(url, kind)
            val container = if (observedContainer != MediaContainer.Unknown) {
                observedContainer
            } else {
                metadata?.container ?: MediaContainer.Unknown
            }
            val video = kind == MediaKind.Video
            val hasAudio = when {
                kind == MediaKind.Audio -> true
                metadata != null -> metadata.hasAudio
                else -> isKnownMuxedItag(itag)
            }
            val kindLabel = if (video) "video" else "audio"
            MediaCandidate(
                id = "webview-$kindLabel-$index-${url.hashCode().toUInt().toString(16)}",
                sourceUrl = url,
                format = MediaFormat(
                    id = itag ?: "webview-$kindLabel-$index",
                    kind = kind,
                    container = container,
                    videoCodec = metadata?.videoCodec,
                    audioCodec = metadata?.audioCodec,
                    width = metadata?.width,
                    height = metadata?.height,
                    fps = metadata?.fps,
                    bitrateKbps = metadata?.bitrateKbps,
                    hasVideo = video,
                    hasAudio = hasAudio,
                ),
                requestHeaders = sessionHeaders + snapshot.browserRequestHeaders[url].orEmpty(),
                sourceContext = com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED,
            )
        }
    }

    private fun mediaKindFromUrl(url: String): MediaKind? {
        val rawMime = Regex("""[?&](?:mime|type)=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val mime = runCatching {
            java.net.URLDecoder.decode(rawMime, "UTF-8")
        }.getOrDefault(rawMime).lowercase()
        return when {
            mime.startsWith("audio/") -> MediaKind.Audio
            mime.startsWith("video/") -> MediaKind.Video
            else -> null
        }
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
            value.contains("wav") -> MediaContainer.Wav
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
        const val MIN_VIDEO_QUALITIES = 4

        const val YOUTUBE_BOT_MESSAGE =
            "YouTube يطلب التحقق من أنك لست روبوتًا. افتح YouTube لتحديث الجلسة ثم أعد المحاولة."

        val KNOWN_MUXED_ITAGS = setOf("18", "22", "37", "43", "44", "45", "46", "59", "78")

        // Format metadata fallback for browser-observed URLs when the Player
        // Response is not available. Height/container are derived only from a
        // recognized itag, not guessed from URL ordering or bitrate.
        val KNOWN_YOUTUBE_FORMATS = mapOf(
            "18" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 640, 360, 30.0, videoCodec = "avc1", audioCodec = "mp4a.40.2", hasAudio = true),
            "22" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1280, 720, 30.0, videoCodec = "avc1", audioCodec = "mp4a.40.2", hasAudio = true),
            "37" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1920, 1080, 30.0, videoCodec = "avc1", audioCodec = "mp4a.40.2", hasAudio = true),
            "43" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 640, 360, 30.0, videoCodec = "vp8", audioCodec = "vorbis", hasAudio = true),
            "44" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 854, 480, 30.0, videoCodec = "vp8", audioCodec = "vorbis", hasAudio = true),
            "45" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 1280, 720, 30.0, videoCodec = "vp8", audioCodec = "vorbis", hasAudio = true),
            "46" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 1920, 1080, 30.0, videoCodec = "vp8", audioCodec = "vorbis", hasAudio = true),
            "59" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 854, 480, 30.0, videoCodec = "avc1", audioCodec = "mp4a.40.2", hasAudio = true),
            "78" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 854, 480, 30.0, videoCodec = "avc1", audioCodec = "mp4a.40.2", hasAudio = true),
            "160" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 256, 144, videoCodec = "avc1"),
            "133" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 426, 240, videoCodec = "avc1"),
            "134" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 640, 360, videoCodec = "avc1"),
            "135" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 854, 480, videoCodec = "avc1"),
            "136" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1280, 720, videoCodec = "avc1"),
            "137" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1920, 1080, videoCodec = "avc1"),
            "264" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 2560, 1440, videoCodec = "avc1"),
            "266" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 3840, 2160, videoCodec = "avc1"),
            "298" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1280, 720, 60.0, videoCodec = "avc1"),
            "299" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1920, 1080, 60.0, videoCodec = "avc1"),
            "242" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 426, 240, videoCodec = "vp9"),
            "243" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 640, 360, videoCodec = "vp9"),
            "244" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 854, 480, videoCodec = "vp9"),
            "247" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 1280, 720, videoCodec = "vp9"),
            "248" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 1920, 1080, videoCodec = "vp9"),
            "271" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 2560, 1440, videoCodec = "vp9"),
            "272" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 3840, 2160, videoCodec = "vp9"),
            "278" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 256, 144, videoCodec = "vp9"),
            "308" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 2560, 1440, 60.0, videoCodec = "vp9"),
            "313" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 3840, 2160, videoCodec = "vp9"),
            "315" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Webm, 3840, 2160, 60.0, videoCodec = "vp9"),
            "394" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 256, 144, videoCodec = "av01"),
            "395" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 426, 240, videoCodec = "av01"),
            "396" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 640, 360, videoCodec = "av01"),
            "397" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 854, 480, videoCodec = "av01"),
            "398" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1280, 720, videoCodec = "av01"),
            "399" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 1920, 1080, videoCodec = "av01"),
            "400" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 2560, 1440, videoCodec = "av01"),
            "401" to KnownYouTubeFormat(MediaKind.Video, MediaContainer.Mp4, 3840, 2160, videoCodec = "av01"),
            "139" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.M4a, bitrateKbps = 48, audioCodec = "mp4a.40.2", hasAudio = true),
            "140" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.M4a, bitrateKbps = 128, audioCodec = "mp4a.40.2", hasAudio = true),
            "141" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.M4a, bitrateKbps = 256, audioCodec = "mp4a.40.2", hasAudio = true),
            "171" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.Webm, bitrateKbps = 128, audioCodec = "vorbis", hasAudio = true),
            "172" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.Webm, bitrateKbps = 256, audioCodec = "vorbis", hasAudio = true),
            "249" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.Webm, bitrateKbps = 50, audioCodec = "opus", hasAudio = true),
            "250" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.Webm, bitrateKbps = 70, audioCodec = "opus", hasAudio = true),
            "251" to KnownYouTubeFormat(MediaKind.Audio, MediaContainer.Webm, bitrateKbps = 160, audioCodec = "opus", hasAudio = true),
        )

        val BOT_CHALLENGE_MARKERS = listOf(
            "Sign in to confirm you’re not a bot",
            "Sign in to confirm you're not a bot",
            "confirm you're not a bot",
            "confirm you’re not a bot",
        )
    }

}
