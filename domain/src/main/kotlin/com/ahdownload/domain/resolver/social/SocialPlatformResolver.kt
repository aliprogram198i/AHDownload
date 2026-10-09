package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.*
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.ahdownload.domain.resolver.browser.WebPageMediaParser
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SocialPlatformResolver(
    private val provider: BrowserMediaSessionProvider,
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val resolveTimeoutMs: Long = SOCIAL_RESOLVE_TIMEOUT_MS,
    private val pageClient: HttpTextClient = OkHttpTextClient(
        client = OkHttpClient.Builder()
            .callTimeout(SOCIAL_PAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(SOCIAL_PAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOCIAL_PAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
    ),
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
            withTimeout(resolveTimeoutMs) {
                logger.log(
                    DiagnosticLevel.INFO,
                    "SOCIAL_RESOLUTION_STARTED",
                    "بدء تحليل المنصة الاجتماعية",
                    "social.resolve",
                    mapOf(
                        "platform" to platform.name,
                        "operation_id" to (request.operationId ?: "none"),
                    ),
                    null,
                )

                val session = provider.snapshot(request.link.normalizedUrl, platform)
                logger.log(
                    DiagnosticLevel.INFO,
                    "SOCIAL_BROWSER_SESSION_RESULT",
                    "اكتملت جلسة المتصفح واستخراج الوسائط الأولية",
                    "social.resolve.browser",
                    mapOf(
                        "platform" to platform.name,
                        "operation_id" to (request.operationId ?: "none"),
                        "media_count" to session.mediaUrls.size.toString(),
                        "headers_count" to session.requestHeadersByUrl.size.toString(),
                        "inspection_attempt_count" to session.inspectionAttemptCount.toString(),
                        "inspection_callback_count" to session.inspectionCallbackCount.toString(),
                        "instagram_api_status" to (
                            session.instagramApiStatus
                                ?: if (platform == MediaPlatform.Instagram) "not_reported" else "not_applicable"
                            ),
                        "title_present" to (!session.title.isNullOrBlank()).toString(),
                        "duration_present" to (session.durationMs != null).toString(),
                    ),
                    null,
                )

                // The final WebView URL may be a redirect to Instagram's login page or a
                // custom-scheme handoff. Start with the user's HTTP(S) request URL instead:
                // OkHttp follows ordinary redirects itself, while this avoids extracting from
                // a login page that replaced the original public post in the WebView.
                val pageUrlCandidate = listOf(
                    "normalized_request_url" to request.link.normalizedUrl,
                    "original_request_url" to request.link.originalUrl,
                    "session_page_url" to session.pageUrl,
                    "final_url" to session.finalUrl,
                ).firstOrNull { (_, value) -> isHttpPageUrl(value) }
                val pageUrl = pageUrlCandidate?.second
                val pageUrlSource = pageUrlCandidate?.first ?: "none"
                var fallback: ParsedPageMedia? = null

                if (pageUrl == null) {
                    logger.log(
                        DiagnosticLevel.WARNING,
                        "SOCIAL_PAGE_FETCH_SKIPPED_INVALID_URL",
                        "تم تخطي جلب HTML لأن عناوين الصفحة المتاحة ليست HTTP أو HTTPS",
                        "social.resolve.page_fetch",
                        mapOf(
                            "platform" to platform.name,
                            "operation_id" to (request.operationId ?: "none"),
                            "final_url_scheme" to urlScheme(session.finalUrl),
                            "session_page_scheme" to urlScheme(session.pageUrl),
                            "request_url_scheme" to urlScheme(request.link.normalizedUrl),
                        ),
                        null,
                    )
                } else {
                    logger.log(
                        DiagnosticLevel.INFO,
                        "SOCIAL_PAGE_FETCH_STARTED",
                        "بدء جلب HTML للصفحة كمسار احتياطي",
                        "social.resolve.page_fetch",
                        mapOf(
                            "platform" to platform.name,
                            "operation_id" to (request.operationId ?: "none"),
                            "page_url_source" to pageUrlSource,
                        ),
                        null,
                    )
                    try {
                        fallback = pageClient.get(pageUrl)
                            .let { WebPageMediaParser.parse(it, pageUrl) }
                        logger.log(
                            DiagnosticLevel.INFO,
                            "SOCIAL_PAGE_FETCH_RESULT",
                            "اكتمل جلب وتحليل HTML الاحتياطي",
                            "social.resolve.page_fetch",
                            mapOf(
                                "platform" to platform.name,
                                "operation_id" to (request.operationId ?: "none"),
                                "page_url_source" to pageUrlSource,
                                "fallback_media_count" to fallback?.mediaUrls?.size?.toString().orEmpty(),
                            ),
                            null,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logger.log(
                            DiagnosticLevel.WARNING,
                            "SOCIAL_PAGE_FETCH_FAILED",
                            error.message ?: error::class.simpleName.orEmpty(),
                            "social.resolve.page_fetch",
                            mapOf(
                                "platform" to platform.name,
                                "operation_id" to (request.operationId ?: "none"),
                                "page_url_source" to pageUrlSource,
                                "exception_type" to error::class.java.simpleName,
                            ),
                            error,
                        )
                    }
                }

                // Instagram may expose media through its public embed even when the
                // shortcode API/GraphQL response is blocked or the original HTML is a login shell.
                // Keep this as a bounded, first-party fallback and never attempt to bypass access controls.
                var instagramEmbedFallback: ParsedPageMedia? = null
                var instagramEmbedResponseChars = 0
                var instagramEmbedLoginWall = false
                var instagramEmbedExceptionType: String? = null
                if (
                    platform == MediaPlatform.Instagram &&
                    !pageUrl.isNullOrBlank() &&
                    session.mediaUrls.isEmpty() &&
                    fallback?.mediaUrls.isNullOrEmpty()
                ) {
                    val embedUrl = instagramEmbedEndpoint(pageUrl)
                    if (embedUrl != null) {
                        val embedHeaders = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.8",
                            "Referer" to pageUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                        )
                        val parsed = try {
                            withTimeoutOrNull(INSTAGRAM_EMBED_FETCH_TIMEOUT_MS) {
                                val body = pageClient.get(embedUrl, embedHeaders)
                                instagramEmbedResponseChars = body.length
                                instagramEmbedLoginWall = looksLikeInstagramLoginWall(body)
                                WebPageMediaParser.parse(body, pageUrl)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            instagramEmbedExceptionType = error::class.java.simpleName
                            null
                        }
                        if (parsed != null && parsed.mediaUrls.isNotEmpty()) {
                            instagramEmbedFallback = parsed
                        }
                        val embedStatus = when {
                            instagramEmbedExceptionType != null -> "request_failed"
                            parsed == null -> "request_timeout"
                            parsed.mediaUrls.isNotEmpty() -> "media_found"
                            instagramEmbedLoginWall -> "login_wall"
                            else -> "no_media"
                        }
                        logger.log(
                            if (embedStatus == "media_found") DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                            "SOCIAL_INSTAGRAM_EMBED_FALLBACK_RESULT",
                            "اكتمل فحص مسار تضمين Instagram العام",
                            "social.resolve.instagram_embed",
                            buildMap {
                                put("platform", platform.name)
                                put("operation_id", request.operationId ?: "none")
                                put("fallback_status", embedStatus)
                                put("response_chars", instagramEmbedResponseChars.toString())
                                put("media_count", (parsed?.mediaUrls?.size ?: 0).toString())
                                put("login_wall_detected", instagramEmbedLoginWall.toString())
                                instagramEmbedExceptionType?.let { put("exception_type", it) }
                            },
                            null,
                        )
                    }
                }

                val title = session.title ?: fallback?.title ?: instagramEmbedFallback?.title
                val thumbnail = session.thumbnailUrl ?: fallback?.thumbnailUrl ?: instagramEmbedFallback?.thumbnailUrl
                val duration = session.durationMs ?: fallback?.durationMs ?: instagramEmbedFallback?.durationMs
                val fallbackMediaCount = fallback?.mediaUrls?.size ?: 0
                val instagramEmbedMediaCount = instagramEmbedFallback?.mediaUrls?.size ?: 0
                val urls = (
                    session.mediaUrls +
                        fallback?.mediaUrls.orEmpty() +
                        instagramEmbedFallback?.mediaUrls.orEmpty()
                    )
                    .distinct()
                    .take(64)

                logger.log(
                    DiagnosticLevel.INFO,
                    "SOCIAL_CANDIDATE_BUILD_STARTED",
                    "بدء بناء المرشحين من مصادر المتصفح والصفحة",
                    "social.resolve.candidates",
                    mapOf(
                        "platform" to platform.name,
                        "operation_id" to (request.operationId ?: "none"),
                        "browser_media_count" to session.mediaUrls.size.toString(),
                        "fallback_media_count" to fallbackMediaCount.toString(),
                        "instagram_embed_media_count" to instagramEmbedMediaCount.toString(),
                        "source_url_count" to urls.size.toString(),
                        "page_url_source" to pageUrlSource,
                    ),
                    null,
                )

                val sessionObservedUrls = session.mediaUrls.toSet()
                var unclassifiedSourceCount = 0
                val builtCandidates = urls.mapIndexedNotNull { index, url ->
                    val audioPresence = session.mediaHasAudioByUrl[url]
                        ?: if (url in sessionObservedUrls) false else null
                    val candidate = inferCandidate(
                        platform = platform,
                        sourceUrl = url,
                        index = index,
                        requestHeaders = session.requestHeadersByUrl[url],
                        audioPresence = audioPresence,
                        sourceContext = if (url in sessionObservedUrls) {
                            MediaSourceContext.BROWSER_OBSERVED
                        } else {
                            MediaSourceContext.RESOLVER_GENERATED
                        },
                    )
                    if (candidate == null) unclassifiedSourceCount++
                    candidate
                }
                // Instagram often exposes a poster/thumbnail beside the playback source.
                // A Reel/video request must not present that image as a downloadable video.
                val rejectedImageCandidateCount = if (request.link.kind == MediaKind.Video) {
                    builtCandidates.count { it.format.kind == MediaKind.Image }
                } else {
                    0
                }
                val candidates = builtCandidates
                    .asSequence()
                    .filterNot {
                        request.link.kind == MediaKind.Video && it.format.kind == MediaKind.Image
                    }
                    .distinctBy {
                        listOf(
                            it.format.kind,
                            it.format.container,
                            it.format.height ?: 0,
                            it.format.bitrateKbps ?: 0,
                            it.sourceUrl,
                        )
                    }
                    .sortedWith(
                        compareByDescending<MediaCandidate> { it.format.kind == MediaKind.Video }
                            .thenByDescending { it.format.height ?: 0 }
                            .thenByDescending { it.format.bitrateKbps ?: 0 },
                    )
                    .toList()

                logger.log(
                    if (candidates.isNotEmpty()) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                    "SOCIAL_RESOLUTION_RESULT",
                    if (candidates.isNotEmpty()) "تم العثور على مصادر وسائط عامة" else "لم يتم العثور على مصدر وسائط مباشر",
                    "social.resolve",
                    mapOf(
                        "platform" to platform.name,
                        "operation_id" to (request.operationId ?: "none"),
                        "candidate_count" to candidates.size.toString(),
                        "rejected_image_candidate_count" to rejectedImageCandidateCount.toString(),
                        "video_candidate_count" to candidates.count { it.format.kind == MediaKind.Video }.toString(),
                        "video_with_audio_count" to candidates.count { it.format.kind == MediaKind.Video && it.format.hasAudio }.toString(),
                        "video_without_audio_count" to candidates.count { it.format.kind == MediaKind.Video && !it.format.hasAudio }.toString(),
                        "video_quality_known_count" to candidates.count { it.format.kind == MediaKind.Video && it.format.height != null }.toString(),
                        "audio_candidate_count" to candidates.count { it.format.kind == MediaKind.Audio }.toString(),
                        "image_candidate_count" to candidates.count { it.format.kind == MediaKind.Image }.toString(),
                        "streaming_manifest_count" to candidates.count { it.streamingManifest }.toString(),
                        "browser_media_count" to session.mediaUrls.size.toString(),
                        "fallback_media_count" to fallbackMediaCount.toString(),
                        "instagram_embed_media_count" to instagramEmbedMediaCount.toString(),
                        "source_url_count" to urls.size.toString(),
                        "unclassified_source_count" to unclassifiedSourceCount.toString(),
                        "page_url_source" to pageUrlSource,
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
            }
        } catch (error: TimeoutCancellationException) {
            logger.log(
                DiagnosticLevel.ERROR,
                "SOCIAL_RESOLUTION_TIMEOUT",
                "انتهت مهلة تحليل المنصة الاجتماعية دون إكمال المسار",
                "social.resolve",
                mapOf(
                    "platform" to platform.name,
                    "timeout_ms" to resolveTimeoutMs.toString(),
                    "operation_id" to (request.operationId ?: "none"),
                ),
                error,
            )
            ResolverResult.Failure(
                FailureCode.ResolverTimeout,
                "استغرق تحليل الصفحة وقتًا أطول من المتوقع. أعد المحاولة.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.log(
                DiagnosticLevel.ERROR,
                "SOCIAL_BROWSER_SESSION_FAILED",
                error.message ?: error::class.simpleName.orEmpty(),
                "social.resolve",
                mapOf(
                    "platform" to platform.name,
                    "operation_id" to (request.operationId ?: "none"),
                ),
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
        audioPresence: Boolean?,
        sourceContext: MediaSourceContext,
    ): MediaCandidate? {
        val mime = runCatching { URI(sourceUrl).rawQuery.orEmpty() }
            .getOrDefault("")
            .split('&')
            .firstNotNullOfOrNull { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                if (key.equals("mime", true) || key.equals("content-type", true) || key.equals("type", true)) {
                    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
                } else null
            }
            .orEmpty()
            .lowercase()

        val path = sourceUrl.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()

        val streamingManifest = extension in setOf("m3u8", "mpd") ||
            mime.contains("mpegurl") ||
            mime.contains("dash+xml")

        val kind = when {
            streamingManifest || mime.startsWith("video") || extension in setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "avi") -> MediaKind.Video
            mime.startsWith("audio") || extension in setOf("mp3", "m4a", "aac", "ogg", "flac", "wav") -> MediaKind.Audio
            mime.startsWith("image") || extension in setOf("jpg", "jpeg", "png", "webp", "gif") -> MediaKind.Image
            else -> return null
        }

        val height = Regex("""(?i)(?:[?&](?:height|h|resolution|quality)=|/)(\d{3,4})p?""")
            .find(sourceUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val container = when {
            streamingManifest -> MediaContainer.Mkv
            extension == "mp4" || mime.contains("mp4") -> MediaContainer.Mp4
            extension == "webm" -> MediaContainer.Webm
            extension == "mov" || mime.contains("quicktime") -> MediaContainer.Mov
            extension == "m4a" -> MediaContainer.M4a
            extension == "mp3" || mime.contains("mpeg") -> MediaContainer.Mp3
            extension == "aac" -> MediaContainer.Aac
            extension == "ogg" -> MediaContainer.Ogg
            extension == "flac" -> MediaContainer.Flac
            else -> MediaContainer.Unknown
        }

        val safeRequestHeaders = requestHeaders.orEmpty()
            .filterKeys(::safeHeader)
            .toMutableMap()
        if (
            platform == MediaPlatform.Instagram &&
            safeRequestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }
        ) {
            // API-extracted CDN URLs have no associated intercepted media request.
            // Preserve normal authenticated-session behavior and add only a safe page referrer.
            safeRequestHeaders["Referer"] = "https://www.instagram.com/"
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
                // Browser-observed media gets an explicit audio-track result when
                // available; do not blindly mark every video request as muxed.
                hasAudio = when (kind) {
                    MediaKind.Audio -> true
                    MediaKind.Video -> audioPresence ?: false
                    MediaKind.Image,
                    MediaKind.Unknown -> false
                },
            ),
            requestHeaders = safeRequestHeaders,
            sessionCookieHost = safeHost(sourceUrl),
            sourceContext = sourceContext,
            streamingManifest = streamingManifest,
        )
    }

    private fun instagramEmbedEndpoint(pageUrl: String): String? {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme?.lowercase() != "https" || host !in setOf("instagram.com", "www.instagram.com")) {
            return null
        }
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val typeIndex = segments.indexOfFirst { it.lowercase() in setOf("p", "reel", "reels", "tv") }
        if (typeIndex < 0) return null
        val mediaType = segments[typeIndex].lowercase()
        val shortcode = segments.getOrNull(typeIndex + 1) ?: return null
        if (!shortcode.matches(Regex("""[A-Za-z0-9_-]{5,}"""))) return null
        return "https://www.instagram.com/$mediaType/$shortcode/embed/captioned/"
    }

    private fun looksLikeInstagramLoginWall(body: String): Boolean {
        val sample = body.take(250_000).lowercase()
        return "accounts/login" in sample ||
            "login_required" in sample ||
            "log in to instagram" in sample ||
            "sign up to see photos and videos" in sample
    }

    private fun isHttpPageUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun urlScheme(value: String?): String {
        if (value.isNullOrBlank()) return "missing"
        return runCatching { URI(value).scheme?.lowercase() ?: "missing" }
            .getOrDefault("invalid")
    }

    private fun safeHeader(name: String): Boolean = when {
        name.equals("Cookie", true) || name.equals("Authorization", true) -> false
        name.equals("User-Agent", true) || name.equals("Referer", true) || name.equals("Origin", true) -> true
        name.equals("Accept", true) || name.equals("Accept-Language", true) -> true
        name.startsWith("Sec-Fetch-", true) -> true
        else -> false
    }

    private fun safeHost(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    private companion object {
        const val SOCIAL_RESOLVE_TIMEOUT_MS = 20_000L
        const val SOCIAL_PAGE_FETCH_TIMEOUT_MS = 8_000L
        const val INSTAGRAM_EMBED_FETCH_TIMEOUT_MS = 3_500L
    }
}
