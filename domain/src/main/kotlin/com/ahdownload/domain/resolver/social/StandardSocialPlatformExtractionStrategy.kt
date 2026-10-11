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
 * Shared policy for platforms whose public pages occasionally hide media metadata in a
 * first-party JSON/mobile representation. It never forwards browser cookies or credentials.
 */
internal open class StandardSocialPlatformExtractionStrategy(
    final override val platform: MediaPlatform,
) : SocialPlatformExtractionStrategy {

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        if (browserMediaCount > 0 || pageFallbackMediaCount > 0 || pageUrl.isNullOrBlank()) return null
        val request = publicFallbackRequest(platform, pageUrl) ?: return null

        var body: String? = null
        var failureType: String? = null
        try {
            body = withTimeoutOrNull(PUBLIC_FALLBACK_TIMEOUT_MS) {
                pageClient.get(
                    request.url,
                    mapOf(
                        "Accept" to "application/json,text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.8",
                        "Referer" to request.referer,
                        "User-Agent" to PUBLIC_FALLBACK_USER_AGENT,
                    ),
                )
            }
        } catch (error: CancellationException) {
            // Preserve cancellation from the caller / lifecycle; never convert it to a fallback result.
            throw error
        } catch (error: Throwable) {
            failureType = error.javaClass.simpleName
        }

        val parsed = body?.let { WebPageMediaParser.parse(it, pageUrl) }
        val mediaCount = parsed?.mediaUrls?.size ?: 0
        val status = when {
            failureType != null -> "request_failed"
            body == null -> "request_timeout"
            mediaCount > 0 -> "media_found"
            else -> "no_media"
        }
        logger.log(
            if (mediaCount > 0) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SOCIAL_PLATFORM_PUBLIC_FALLBACK_RESULT",
            "اكتمل فحص المسار العام البديل للمنصة",
            "social.resolve.platform_fallback",
            mapOf(
                "platform" to platform.name,
                "operation_id" to (operationId ?: "none"),
                "fallback_kind" to request.kind,
                "response_chars" to (body?.length ?: 0).toString(),
                "media_count" to mediaCount.toString(),
                "fallback_status" to status,
                "exception_type" to (failureType ?: "none"),
            ),
            null,
        )
        return parsed?.takeIf { it.mediaUrls.isNotEmpty() }
    }

    override fun requestHeadersForCandidate(
        sourceUrl: String,
        safeHeaders: Map<String, String>,
    ): Map<String, String> {
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        val host = runCatching { URI(sourceUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val referer = when {
            host == "v.redd.it" || host.endsWith(".redditmedia.com") -> "https://www.reddit.com/"
            host == "video.twimg.com" -> "https://x.com/"
            host.endsWith(".fbcdn.net") || host.endsWith(".fbsbx.com") -> "https://www.facebook.com/"
            host == "pinimg.com" || host.endsWith(".pinimg.com") -> "https://www.pinterest.com/"
            host.endsWith(".vimeocdn.com") -> "https://vimeo.com/"
            else -> null
        } ?: return safeHeaders
        return safeHeaders + ("Referer" to referer)
    }

    private fun publicFallbackRequest(platform: MediaPlatform, pageUrl: String): PublicFallbackRequest? {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") || uri.userInfo != null || host.isBlank()) return null
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()

        return when (platform) {
            MediaPlatform.Facebook -> {
                if (!isHostOrSubdomain(host, "facebook.com") && !isHostOrSubdomain(host, "fb.watch")) return null
                // Use the public mobile web representation; no login cookie is sent.
                PublicFallbackRequest(
                    kind = "facebook_mobile_web",
                    url = "https://m.facebook.com$path$query",
                    referer = "https://www.facebook.com/",
                )
            }
            MediaPlatform.X -> {
                if (!isHostOrSubdomain(host, "x.com") && !isHostOrSubdomain(host, "twitter.com")) return null
                val statusId = Regex("""/(?:status|statuses)/(\d+)""")
                    .find(uri.path.orEmpty())?.groupValues?.getOrNull(1) ?: return null
                PublicFallbackRequest(
                    kind = "x_public_syndication",
                    url = "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&lang=en",
                    referer = "https://x.com/",
                )
            }
            MediaPlatform.Reddit -> {
                if (!isHostOrSubdomain(host, "reddit.com")) return null
                val normalizedPath = path.trimEnd('/').ifBlank { "/" }
                val jsonPath = if (normalizedPath.endsWith(".json", ignoreCase = true)) normalizedPath else "$normalizedPath.json"
                val separator = if (query.isBlank()) "?" else "&"
                PublicFallbackRequest(
                    kind = "reddit_public_json",
                    url = "https://${uri.rawAuthority}$jsonPath$query${separator}raw_json=1",
                    referer = "https://www.reddit.com/",
                )
            }
            MediaPlatform.Pinterest -> {
                if (!isHostOrSubdomain(host, "pinterest.com")) return null
                val pinId = Regex("""/pin/(\d+)""")
                    .find(uri.path.orEmpty())?.groupValues?.getOrNull(1) ?: return null
                PublicFallbackRequest(
                    kind = "pinterest_public_widget_payload",
                    url = "https://widgets.pinterest.com/v3/pidgets/pins/info/?pin_ids=$pinId&sub=www&base_scheme=https",
                    referer = "https://www.pinterest.com/",
                )
            }
            MediaPlatform.Vimeo -> {
                if (!isHostOrSubdomain(host, "vimeo.com")) return null
                val videoId = uri.path.orEmpty().split('/').lastOrNull()
                    ?.takeIf { it.matches(Regex("""\d{4,}""")) } ?: return null
                PublicFallbackRequest(
                    kind = "vimeo_public_player_config",
                    url = "https://player.vimeo.com/video/$videoId/config",
                    referer = "https://vimeo.com/",
                )
            }
            else -> null
        }
    }

    private fun isHostOrSubdomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private data class PublicFallbackRequest(
        val kind: String,
        val url: String,
        val referer: String,
    )

    private companion object {
        const val PUBLIC_FALLBACK_TIMEOUT_MS = 5_000L
        const val PUBLIC_FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
