package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.ahdownload.domain.resolver.browser.WebPageMediaParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

        val parsed = body?.let {
            if (platform == MediaPlatform.Vimeo) {
                parseVimeoProgressiveMedia(it, pageUrl)
            } else {
                WebPageMediaParser.parse(it, pageUrl)
            }
        }
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
                // Facebook's public video plugin returns an embeddable representation when the
                // post owner permits public embedding. No login cookie or session is forwarded.
                val encodedPageUrl = URLEncoder.encode(pageUrl, StandardCharsets.UTF_8.name())
                PublicFallbackRequest(
                    kind = "facebook_public_video_embed",
                    url = "https://www.facebook.com/plugins/video.php?href=" +
                        encodedPageUrl + "&show_text=0&width=560",
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


    private fun parseVimeoProgressiveMedia(body: String, pageUrl: String): ParsedPageMedia {
        val generic = WebPageMediaParser.parse(body, pageUrl)
        val root = runCatching { JsonParser.parseString(body) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return generic.copy(mediaUrls = generic.mediaUrls.filter(::isDirectVimeoMediaUrl))

        val video = root.get("video")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val request = root.get("request")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject

        fun progressiveUrls(files: JsonObject?): List<String> {
            val progressive = files
                ?.get("progressive")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: return emptyList()
            return progressive.mapNotNull { entry ->
                if (!entry.isJsonObject) return@mapNotNull null
                val url = entry.asJsonObject.get("url")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?: return@mapNotNull null
                url.takeIf(::isAllowedVimeoProgressiveUrl)
            }
        }

        val videoFiles = video?.get("files")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val requestFiles = request?.get("files")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val progressiveUrls = (progressiveUrls(videoFiles) + progressiveUrls(requestFiles))
            .distinct()
            .take(16)

        // Prefer only direct progressive files. HLS/DASH manifest URLs are valid HTTP
        // responses, but this app's current download pipeline must not label them as ordinary
        // downloadable MP4 files.
        val urls = if (progressiveUrls.isNotEmpty()) {
            progressiveUrls
        } else {
            generic.mediaUrls.filter(::isDirectVimeoMediaUrl)
        }
        val title = video?.get("title")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return generic.copy(
            title = generic.title ?: title,
            mediaUrls = urls,
        )
    }

    private fun isAllowedVimeoProgressiveUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "vimeocdn.com" ||
            host.endsWith(".vimeocdn.com") ||
            host == "akamaized.net" ||
            host.endsWith(".akamaized.net") ||
            host == "vimeo.com" ||
            host.endsWith(".vimeo.com")
    }

    private fun isDirectVimeoMediaUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "vimeocdn.com" ||
                host.endsWith(".vimeocdn.com") ||
                host == "akamaized.net" ||
                host.endsWith(".akamaized.net") ||
                host == "vimeo.com" ||
                host.endsWith(".vimeo.com"))
        ) return false
        return uri.path.orEmpty().substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mov", "webm")
    }

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
