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
 * Facebook-specific public HTML fallback. The shared resolver tries the original URL first;
 * this strategy retries once on Facebook's mobile host if no media was captured.
 */
internal class FacebookSocialPlatformExtractionStrategy :
    StandardSocialPlatformExtractionStrategy(MediaPlatform.Facebook) {

    override val additionalMediaMetricName: String = "facebook_host_fallback_media_count"

    override fun pageRequestHeaders(pageUrl: String): Map<String, String> =
        if (isFacebookPageUrl(pageUrl)) requestHeaders() else emptyMap()

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        if (pageUrl.isNullOrBlank() || browserMediaCount > 0 || pageFallbackMediaCount > 0) return null
        val fallbacks = fallbackUrls(pageUrl)
        if (fallbacks.isEmpty()) return null

        for ((variant, fallbackUrl) in fallbacks) {
            var body: String? = null
            var parsed: ParsedPageMedia? = null
            var exceptionType: String? = null
            var httpStatus: String? = null

            try {
                body = withTimeoutOrNull(FACEBOOK_FALLBACK_FETCH_TIMEOUT_MS) {
                    pageClient.get(
                        fallbackUrl,
                        requestHeaders(),
                        mapOf(
                            "platform" to platform.name,
                            "operation_id" to (operationId ?: "none"),
                            "stage" to "RESOLUTION",
                            "diagnostic_operation" to "social.resolve.facebook_fallback",
                            "request_role" to "facebook_host_fallback",
                            "fallback_variant" to variant,
                        ),
                    )
                }
                parsed = body?.let { WebPageMediaParser.parse(it, fallbackUrl) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                exceptionType = error::class.java.simpleName
                httpStatus = HTTP_STATUS_PATTERN.find(error.message.orEmpty())
                    ?.groupValues?.getOrNull(1)
            }

            val mediaCount = parsed?.mediaUrls?.size ?: 0
            val status = when {
                exceptionType != null -> "request_failed"
                body == null -> "request_timeout"
                mediaCount > 0 -> "media_found"
                looksLikeLoginWall(body) -> "login_wall"
                else -> "no_media"
            }
            logger.log(
                if (mediaCount > 0) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                "SOCIAL_FACEBOOK_FALLBACK_RESULT",
                "اكتمل فحص مضيف Facebook الاحتياطي",
                "social.resolve.facebook_fallback",
                buildMap {
                    put("platform", platform.name)
                    put("operation_id", operationId ?: "none")
                    put("fallback_variant", variant)
                    put("fallback_status", status)
                    put("response_chars", (body?.length ?: 0).toString())
                    put("media_count", mediaCount.toString())
                    put("login_wall_detected", (body?.let(::looksLikeLoginWall) ?: false).toString())
                    httpStatus?.let { put("http_status", it) }
                    body?.let { putAll(responseMarkers(it)) }
                    exceptionType?.let { put("exception_type", it) }
                },
                null,
            )
            if (mediaCount > 0) return parsed
        }
        return null
    }

    override fun requestHeadersForCandidate(
        sourceUrl: String,
        safeHeaders: Map<String, String>,
    ): Map<String, String> {
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        return safeHeaders + ("Referer" to FACEBOOK_REFERER)
    }

    private fun fallbackUrls(pageUrl: String): List<Pair<String, String>> {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return emptyList()
        val host = uri.host?.lowercase() ?: return emptyList()
        if (host !in FACEBOOK_PAGE_HOSTS) return emptyList()

        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        // Keep attempts bounded and restricted to Facebook-owned hosts.
        val targets = listOf(
            "m.facebook.com" to "mobile_host",
            "mbasic.facebook.com" to "mbasic_host",
            "www.facebook.com" to "desktop_host",
            "web.facebook.com" to "web_host",
        )
        return targets
            .filterNot { (targetHost, _) -> targetHost == host }
            .distinctBy { it.first }
            .take(MAX_FACEBOOK_HOST_FALLBACKS)
            .map { (targetHost, variant) -> variant to "https://$targetHost$path$query" }
            .filterNot { (_, alternate) -> alternate.equals(pageUrl, ignoreCase = true) }
    }

    private fun isFacebookPageUrl(pageUrl: String): Boolean {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            host in FACEBOOK_PAGE_HOSTS
    }

    private fun requestHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.8",
        "Referer" to FACEBOOK_REFERER,
        "User-Agent" to FACEBOOK_USER_AGENT,
    )

    private fun looksLikeLoginWall(body: String): Boolean {
        val sample = body.take(MAX_MARKER_SCAN_CHARS).lowercase()
        return "login.php" in sample ||
            "login_required" in sample ||
            "log in to facebook" in sample ||
            "you must log in" in sample ||
            "checkpoint" in sample
    }

    /** Emit marker booleans only; never persist response HTML or media URLs. */
    private fun responseMarkers(body: String): Map<String, String> {
        val sample = body.take(MAX_MARKER_SCAN_CHARS).lowercase()
        fun has(vararg values: String): String = values.any { it in sample }.toString()
        return mapOf(
            "marker_og_video" to has("og:video"),
            "marker_video_element" to has("<video"),
            "marker_video_url_key" to has("video_url", "playback_url"),
            "marker_native_video_key" to has("browser_native_hd_url", "browser_native_sd_url"),
            "marker_playable_url_key" to has("playable_url"),
            "marker_fbcdn" to has("fbcdn.net"),
            "marker_login_or_checkpoint" to has("login.php", "login_required", "checkpoint"),
        )
    }

    private companion object {
        val FACEBOOK_PAGE_HOSTS = setOf(
            "facebook.com",
            "www.facebook.com",
            "m.facebook.com",
            "mbasic.facebook.com",
            "web.facebook.com",
        )
        val HTTP_STATUS_PATTERN = Regex("""(?i)\bHTTP\s+(\d{3})\b""")
        const val FACEBOOK_FALLBACK_FETCH_TIMEOUT_MS = 2_000L
        const val MAX_FACEBOOK_HOST_FALLBACKS = 3
        const val MAX_MARKER_SCAN_CHARS = 500_000
        const val FACEBOOK_REFERER = "https://www.facebook.com/"
        const val FACEBOOK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
