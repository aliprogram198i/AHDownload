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
                        "media_count" to session.mediaUrls.size.toString(),
                        "headers_count" to session.requestHeadersByUrl.size.toString(),
                        "title_present" to (!session.title.isNullOrBlank()).toString(),
                        "duration_present" to (session.durationMs != null).toString(),
                    ),
                    null,
                )

                // WebView may follow a platform deep link (for example instagram://)
                // after the initial HTTPS navigation. Only send valid HTTP(S) URLs to OkHttp.
                // Prefer the final URL when it is web-safe, then fall back through the original
                // session/request URLs rather than allowing a custom scheme to break resolution.
                val pageUrlCandidate = listOf(
                    "final_url" to session.finalUrl,
                    "session_page_url" to session.pageUrl,
                    "normalized_request_url" to request.link.normalizedUrl,
                    "original_request_url" to request.link.originalUrl,
                ).firstOrNull { (_, value) -> isHttpPageUrl(value) }
                val pageUrl = pageUrlCandidate?.second
                var fallback: ParsedPageMedia? = null

                if (pageUrl == null) {
                    logger.log(
                        DiagnosticLevel.WARNING,
                        "SOCIAL_PAGE_FETCH_SKIPPED_INVALID_URL",
                        "تم تخطي جلب HTML لأن عناوين الصفحة المتاحة ليست HTTP أو HTTPS",
                        "social.resolve.page_fetch",
                        mapOf(
                            "platform" to platform.name,
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
                            "page_url_source" to pageUrlCandidate.first,
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
                                "exception_type" to error::class.java.simpleName,
                            ),
                            error,
                        )
                    }
                }

                val title = session.title ?: fallback?.title
                val thumbnail = session.thumbnailUrl ?: fallback?.thumbnailUrl
                val duration = session.durationMs ?: fallback?.durationMs
                val urls = (session.mediaUrls + fallback?.mediaUrls.orEmpty())
                    .distinct()
                    .take(64)

                logger.log(
                    DiagnosticLevel.INFO,
                    "SOCIAL_CANDIDATE_BUILD_STARTED",
                    "بدء بناء المرشحين من مصادر المتصفح والصفحة",
                    "social.resolve.candidates",
                    mapOf(
                        "platform" to platform.name,
                        "source_url_count" to urls.size.toString(),
                    ),
                    null,
                )

                val sessionObservedUrls = session.mediaUrls.toSet()
                val candidates = urls.mapIndexedNotNull { index, url ->
                    val audioPresence = session.mediaHasAudioByUrl[url]
                        ?: if (url in sessionObservedUrls) false else null
                    inferCandidate(
                        platform = platform,
                        sourceUrl = url,
                        index = index,
                        requestHeaders = session.requestHeadersByUrl[url],
                        audioPresence = audioPresence,
                    )
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
        audioPresence: Boolean?,
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
            requestHeaders = requestHeaders.orEmpty().filterKeys(::safeHeader),
            sessionCookieHost = safeHost(sourceUrl),
            streamingManifest = streamingManifest,
        )
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
    }
}
