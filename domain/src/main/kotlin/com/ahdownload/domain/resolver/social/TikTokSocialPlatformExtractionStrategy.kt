package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.ahdownload.domain.resolver.browser.WebPageMediaParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TikTok-specific public-page fallback. The shared resolver remains responsible for candidate
 * classification and validation; this strategy only discovers media explicitly exposed by a
 * public video embed. It does not bypass login, consent, or access restrictions.
 */
internal class TikTokSocialPlatformExtractionStrategy :
    StandardSocialPlatformExtractionStrategy(MediaPlatform.TikTok) {

    override val additionalMediaMetricName: String = "tiktok_embed_media_count"

    override fun pageRequestHeaders(pageUrl: String): Map<String, String> =
        if (!isTikTokPageUrl(pageUrl)) emptyMap() else requestHeaders(pageUrl)

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        // The embed is a last-resort path only. Never replace media already observed by the
        // browser or parsed from the original public page.
        if (browserMediaCount > 0 || pageFallbackMediaCount > 0) return null
        val safePageUrl = pageUrl?.takeIf { it.isNotBlank() }
        if (safePageUrl == null) {
            logEmbedFallbackSkipped(logger, operationId, "page_url_missing", "none")
            return null
        }
        val videoId = extractVideoId(safePageUrl)
        if (videoId == null) {
            val isTikTokPage = isTikTokPageUrl(safePageUrl)
            logEmbedFallbackSkipped(
                logger = logger,
                operationId = operationId,
                reason = if (isTikTokPage) "video_id_not_found" else "unsupported_page_url",
                hostCategory = if (isTikTokPage) "tiktok" else "other_or_unknown",
            )
            return null
        }
        val embedUrl = "$TIKTOK_ORIGIN/embed/v2/$videoId"
        var body: String? = null
        var exceptionType: String? = null
        var httpStatus: Int? = null

        try {
            body = withTimeoutOrNull(EMBED_FETCH_TIMEOUT_MS) {
                pageClient.get(embedUrl, requestHeaders(safePageUrl), mapOf(
                    "platform" to MediaPlatform.TikTok.name,
                    "stage" to "SOCIAL_TIKTOK_PUBLIC_EMBED",
                    "operation_id" to (operationId ?: "none"),
                    "diagnostic_operation" to "social.resolve.tiktok_embed",
                ))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Do not log exception messages: they can contain full URLs or query tokens.
            exceptionType = error::class.java.simpleName
            httpStatus = Regex("""(?i)\bHTTP\s+(\d{3})\b""")
                .find(error.message.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }

        val parsed = body?.let { parsePublicEmbed(it, safePageUrl) }
        val mediaFound = parsed?.mediaUrls?.isNotEmpty() == true
        val sample = body.orEmpty().take(MAX_DIAGNOSTIC_SAMPLE).lowercase()
        val status = when {
            httpStatus == 451 -> "http_451_unavailable"
            httpStatus != null -> "http_error"
            exceptionType != null -> "request_failed"
            body == null -> "request_timeout"
            mediaFound -> "media_found"
            listOf("captcha", "verify to continue", "access denied", "login required").any { it in sample } -> "access_gate_or_challenge"
            else -> "no_public_media"
        }

        logger.log(
            if (mediaFound) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SOCIAL_TIKTOK_EMBED_FALLBACK_RESULT",
            if (httpStatus == 451) {
                "تعذر الوصول إلى مشغّل TikTok العام (HTTP 451)"
            } else {
                "اكتمل فحص مشغّل TikTok العام الاحتياطي"
            },
            "social.resolve.tiktok_embed",
            buildMap {
                put("platform", MediaPlatform.TikTok.name)
                put("operation_id", operationId ?: "none")
                put("fallback_status", status)
                put("response_chars", (body?.length ?: 0).toString())
                put("media_count", (parsed?.mediaUrls?.size ?: 0).toString())
                put("video_id_present", "true")
                httpStatus?.let { put("http_status", it.toString()) }
                put("marker_play_addr", ("playAddr" in body.orEmpty()).toString())
                put("marker_download_addr", ("downloadAddr" in body.orEmpty()).toString())
                put("marker_og_video", ("og:video" in sample).toString())
                exceptionType?.let { put("exception_type", it) }
            },
            null,
        )
        return parsed?.takeIf { it.mediaUrls.isNotEmpty() }
    }

    private fun parsePublicEmbed(html: String, baseUrl: String): ParsedPageMedia {
        val generic = WebPageMediaParser.parse(html, baseUrl)
        val normalizedHtml = normalizeEscapedJson(html)
        val keyedUrls = MEDIA_URL_FIELD_REGEX.findAll(normalizedHtml)
            .mapNotNull { match ->
                normalizeUrl(match.groupValues[1])
                    .takeIf(::isTikTokVideoMediaUrl)
            }
            .toList()

        // Embed markup can expose a CDN URL without a stable field name such as playAddr.
        // Scan URL literals as a final fallback, then apply the same strict TikTok CDN
        // host/path validation used for keyed media values.
        val scannedUrls = Regex("""(?i)https?://[^\s"'<>\\]+""")
            .findAll(normalizedHtml)
            .mapNotNull { match ->
                normalizeUrl(match.value.trimEnd(',', ';', ')', ']', '.'))
                    .takeIf(::isTikTokVideoMediaUrl)
            }
            .toList()

        val safeUrls = (generic.mediaUrls + keyedUrls + scannedUrls)
            .filter(::isTikTokVideoMediaUrl)
            .distinct()
            .take(MAX_MEDIA_URLS)
        return generic.copy(mediaUrls = safeUrls)
    }

    private fun extractVideoId(pageUrl: String?): String? = extractTikTokVideoId(pageUrl)

    private fun isTikTokPageUrl(value: String?): Boolean = runCatching {
        if (value.isNullOrBlank()) return@runCatching false
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return@runCatching false
        uri.scheme?.lowercase() == "https" &&
            (host == "tiktok.com" || host.endsWith(".tiktok.com"))
    }.getOrDefault(false)

    private fun isTikTokVideoMediaUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return@runCatching false
        if (uri.scheme?.lowercase() != "https" || !TIKTOK_MEDIA_HOST_SUFFIXES.any {
                host == it || host.endsWith(".$it")
            }
        ) return@runCatching false

        val path = uri.path.orEmpty().lowercase()
        val extension = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        if (extension in IMAGE_EXTENSIONS) return@runCatching false

        val query = uri.rawQuery.orEmpty()
        extension in VIDEO_EXTENSIONS ||
            query.contains("mime_type=video", ignoreCase = true) ||
            query.contains("content-type=video", ignoreCase = true) ||
            "/video/tos/" in path ||
            Regex("""/video/tos(?:[-/]|$)""").containsMatchIn(path)
    }.getOrDefault(false)

    private fun requestHeaders(referer: String): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.8",
        "Referer" to referer,
        "Origin" to TIKTOK_ORIGIN,
        "User-Agent" to TIKTOK_USER_AGENT,
    )


    private fun logEmbedFallbackSkipped(
        logger: DiagnosticLogger,
        operationId: String?,
        reason: String,
        hostCategory: String,
    ) {
        logger.log(
            DiagnosticLevel.WARNING,
            "SOCIAL_TIKTOK_EMBED_FALLBACK_SKIPPED",
            "تم تخطي فحص مشغّل TikTok العام لعدم توفر معرّف فيديو صالح",
            "social.resolve.tiktok_embed",
            mapOf(
                "platform" to MediaPlatform.TikTok.name,
                "operation_id" to (operationId ?: "none"),
                "fallback_status" to "skipped",
                "skip_reason" to reason,
                "page_url_host_category" to hostCategory,
                "video_id_present" to "false",
            ),
            null,
        )
    }

    private fun normalizeEscapedJson(value: String): String = value
        .replace(Regex("""\\+u002f""", RegexOption.IGNORE_CASE), "/")
        .replace(Regex("""\\+u0026""", RegexOption.IGNORE_CASE), "&")
        .replace(Regex("""\\+u003f""", RegexOption.IGNORE_CASE), "?")
        .replace(Regex("""\\+u003d""", RegexOption.IGNORE_CASE), "=")
        .replace(Regex("""\\+/"""), "/")
        .replace("&amp;", "&")

    private fun normalizeUrl(value: String): String = normalizeEscapedJson(value)
        .trim()
        .takeIf { it.startsWith("https://", ignoreCase = true) }
        .orEmpty()

    private companion object {
        const val TIKTOK_ORIGIN = "https://www.tiktok.com"
        const val EMBED_FETCH_TIMEOUT_MS = 3_500L
        const val MAX_DIAGNOSTIC_SAMPLE = 250_000
        const val MAX_MEDIA_URLS = 16
        const val TIKTOK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "m3u8", "mpd")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
        val TIKTOK_MEDIA_HOST_SUFFIXES = setOf(
            "tiktokcdn.com",
            "tiktokcdn-us.com",
            "tiktokv.com",
            "byteoversea.com",
            "ibytedtos.com",
            "muscdn.com",
        )
        val MEDIA_URL_FIELD_REGEX = Regex(
            """(?is)["']?(?:playAddr|downloadAddr|play_addr|download_addr|video_url|videoUrl|playback_url|contentUrl|content_url)["']?\s*:\s*["']([^"']+)["']""",
        )
    }
}

/** Returns a video ID only from a canonical HTTPS TikTok video URL. */
internal fun extractTikTokVideoId(pageUrl: String?): String? = runCatching {
    val value = pageUrl?.takeIf { it.isNotBlank() } ?: return@runCatching null
    val uri = URI(value)
    val host = uri.host?.lowercase() ?: return@runCatching null
    if (uri.scheme?.lowercase() != "https" ||
        (host != "tiktok.com" && !host.endsWith(".tiktok.com"))
    ) return@runCatching null
    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    val videoIndex = segments.indexOfLast { it.equals("video", ignoreCase = true) }
    segments.getOrNull(videoIndex + 1)
        ?.takeIf { videoIndex >= 0 && it.matches(Regex("""\d{6,30}""")) }
}.getOrNull()

internal fun isTikTokCanonicalVideoUrl(value: String?): Boolean =
    extractTikTokVideoId(value) != null
