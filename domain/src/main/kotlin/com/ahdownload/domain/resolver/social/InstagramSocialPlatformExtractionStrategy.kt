package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.ahdownload.domain.resolver.browser.WebPageMediaParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Instagram-specific public-embed fallback, timeout, diagnostics and request-context policy. */
internal class InstagramSocialPlatformExtractionStrategy : SocialPlatformExtractionStrategy {
    override val platform: MediaPlatform = MediaPlatform.Instagram
    override val additionalMediaMetricName: String = "instagram_embed_media_count"

    override fun effectiveTimeoutMillis(configuredTimeoutMs: Long): Long =
        if (configuredTimeoutMs == SOCIAL_RESOLVE_TIMEOUT_MS) INSTAGRAM_SOCIAL_RESOLVE_TIMEOUT_MS
        else configuredTimeoutMs

    override fun sessionInspectionStatus(session: BrowserMediaSession): String =
        session.instagramApiStatus ?: "not_reported"

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        if (pageUrl.isNullOrBlank() || browserMediaCount != 0 || pageFallbackMediaCount != 0) return null
        val endpoints = instagramEmbedEndpoints(pageUrl)
        if (endpoints.isEmpty()) return null
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.8",
            "Referer" to pageUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        )
        for ((variant, embedUrl) in endpoints) {
            var parsed: ParsedPageMedia? = null
            var body: String? = null
            var loginWall = false
            var exceptionType: String? = null
            try {
                body = withTimeoutOrNull(INSTAGRAM_EMBED_FETCH_TIMEOUT_MS) { pageClient.get(embedUrl, headers) }
                if (body != null) {
                    loginWall = looksLikeInstagramLoginWall(body)
                    parsed = WebPageMediaParser.parse(body, pageUrl)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                exceptionType = error::class.java.simpleName
            }
            val hasMedia = parsed?.mediaUrls?.isNotEmpty() == true
            val status = when {
                exceptionType != null -> "request_failed"
                body == null -> "request_timeout"
                hasMedia -> "media_found"
                loginWall -> "login_wall"
                else -> "no_media"
            }
            logger.log(
                if (hasMedia) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                "SOCIAL_INSTAGRAM_EMBED_FALLBACK_RESULT",
                "اكتمل فحص مسار تضمين Instagram العام",
                "social.resolve.instagram_embed",
                buildMap {
                    put("platform", platform.name)
                    put("operation_id", operationId ?: "none")
                    put("embed_variant", variant)
                    put("fallback_status", status)
                    put("response_chars", (body?.length ?: 0).toString())
                    put("media_count", (parsed?.mediaUrls?.size ?: 0).toString())
                    put("login_wall_detected", loginWall.toString())
                    body?.let { putAll(instagramEmbedMarkers(it)) }
                    exceptionType?.let { put("exception_type", it) }
                },
                null,
            )
            if (hasMedia) return parsed
        }
        return null
    }

    override fun requestHeadersForCandidate(sourceUrl: String, safeHeaders: Map<String, String>): Map<String, String> {
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        return safeHeaders + ("Referer" to "https://www.instagram.com/")
    }

    private fun instagramEmbedEndpoints(pageUrl: String): List<Pair<String, String>> {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme?.lowercase() != "https" || host !in setOf("instagram.com", "www.instagram.com")) return emptyList()
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val typeIndex = segments.indexOfFirst { it.lowercase() in setOf("p", "reel", "reels", "tv") }
        if (typeIndex < 0) return emptyList()
        val mediaType = segments[typeIndex].lowercase()
        val shortcode = segments.getOrNull(typeIndex + 1) ?: return emptyList()
        if (!shortcode.matches(Regex("""[A-Za-z0-9_-]{5,}"""))) return emptyList()
        val base = "https://www.instagram.com/$mediaType/$shortcode"
        return listOf("captioned" to "$base/embed/captioned/", "plain" to "$base/embed/")
    }

    private fun looksLikeInstagramLoginWall(body: String): Boolean {
        val sample = body.take(250_000).lowercase()
        return "accounts/login" in sample || "login_required" in sample ||
            "log in to instagram" in sample || "sign up to see photos and videos" in sample
    }

    /** Emits booleans only; never logs HTML, media URLs, cookies, or signed query strings. */
    private fun instagramEmbedMarkers(body: String): Map<String, String> {
        val sample = body.take(800_000).lowercase()
        fun has(vararg markers: String) = markers.any { it in sample }.toString()
        return mapOf(
            "marker_video_url_key" to has("video_url"),
            "marker_video_versions_key" to has("video_versions"),
            "marker_playback_url_key" to has("playback_url"),
            "marker_content_url_key" to has("contenturl", "content_url"),
            "marker_og_video" to has("og:video"),
            "marker_video_element" to has("<video"),
            "marker_instagram_cdn" to has("cdninstagram", "fbcdn.net", "scontent"),
            "marker_media_extension" to has(".mp4", ".m4v", ".webm", ".m3u8", ".mpd"),
            "marker_video_metadata" to has("video_versions", "video_url", "playback_url", "contenturl", "og:video"),
            "marker_login_or_checkpoint" to has("accounts/login", "login_required", "checkpoint_required"),
            "marker_challenge_or_rate_limit" to has("challenge_required", "please wait a few minutes", "rate limit"),
            "marker_response_error" to has("graphql_error", "feedback_required", "restricted_access"),
        )
    }

    private companion object {
        const val INSTAGRAM_SOCIAL_RESOLVE_TIMEOUT_MS = 32_000L
        const val INSTAGRAM_EMBED_FETCH_TIMEOUT_MS = 3_500L
    }
}
