package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * X-specific fallback for public posts whose browser session and HTML do not expose
 * a directly playable media URL. It uses the public tweet syndication response only;
 * browser-observed media and the shared HTML extraction remain the primary paths.
 */
internal class XSocialPlatformExtractionStrategy : StandardSocialPlatformExtractionStrategy(MediaPlatform.X) {
    override val additionalMediaMetricName: String = "x_syndication_media_count"

    override fun effectiveTimeoutMillis(configuredTimeoutMs: Long): Long =
        if (configuredTimeoutMs == SOCIAL_RESOLVE_TIMEOUT_MS) X_SOCIAL_RESOLVE_TIMEOUT_MS
        else configuredTimeoutMs

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        // Avoid extra network traffic when an earlier extraction path already
        // found media. The syndication fallback is deliberately bounded.
        if (browserMediaCount > 0 || pageFallbackMediaCount > 0) return null

        val statusId = extractStatusId(pageUrl) ?: return null
        val requestUrl = "$SYNDICATION_ENDPOINT?id=$statusId&lang=en&token=0"
        val headers = mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.8",
            "Referer" to (pageUrl ?: X_REFERER),
        )

        var responseBody: String? = null
        var exceptionType: String? = null
        try {
            responseBody = withTimeoutOrNull(X_SYNDICATION_FETCH_TIMEOUT_MS) {
                pageClient.get(
                    requestUrl,
                    headers,
                    mapOf(
                        "platform" to MediaPlatform.X.name,
                        "stage" to "SOCIAL_X_SYNDICATION",
                        "operation_id" to (operationId ?: "none"),
                        "diagnostic_operation" to "social.resolve.x_syndication",
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Do not log the exception message: HTTP clients may include request URLs.
            exceptionType = error::class.java.simpleName
        }

        val parsed = responseBody?.let { parseSyndicationResponse(it) }
        val hasMedia = parsed?.mediaUrls?.isNotEmpty() == true
        val status = when {
            exceptionType != null -> "request_failed"
            responseBody == null -> "request_timeout"
            hasMedia -> "media_found"
            else -> "no_video_media"
        }

        logger.log(
            if (hasMedia) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SOCIAL_X_SYNDICATION_FALLBACK_RESULT",
            "اكتمل فحص مسار X Syndication العام",
            "social.resolve.x_syndication",
            buildMap {
                put("platform", MediaPlatform.X.name)
                put("operation_id", operationId ?: "none")
                put("fallback_status", status)
                put("response_chars", (responseBody?.length ?: 0).toString())
                put("media_count", (parsed?.mediaUrls?.size ?: 0).toString())
                put("status_id_present", "true")
                exceptionType?.let { put("exception_type", it) }
            },
            null,
        )

        return parsed?.takeIf { it.mediaUrls.isNotEmpty() }
    }

    override fun requestHeadersForCandidate(
        sourceUrl: String,
        safeHeaders: Map<String, String>,
    ): Map<String, String> {
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        return safeHeaders + ("Referer" to X_REFERER)
    }

    private fun extractStatusId(pageUrl: String?): String? {
        if (pageUrl.isNullOrBlank()) return null
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null

        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("mobile.") ?: return null
        val isXHost = host == "x.com" || host.endsWith(".x.com") ||
            host == "twitter.com" || host.endsWith(".twitter.com")
        if (!isXHost) return null

        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val statusIndex = segments.indexOfLast { it.equals("status", ignoreCase = true) }
        if (statusIndex < 0) return null

        return segments.getOrNull(statusIndex + 1)
            ?.takeIf { it.length in 1..40 && it.all(Char::isDigit) }
    }

    private fun parseSyndicationResponse(body: String): ParsedPageMedia? {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null

        val mediaUrls = linkedSetOf<String>()
        var thumbnailUrl: String? = null
        var durationMs: Long? = null

        val legacy = root.getAsJsonObject("legacy")
        val title = firstString(root, "text", "full_text")
            ?: legacy?.let { firstString(it, "full_text", "text") }

        fun visit(element: JsonElement) {
            if (element.isJsonArray) {
                element.asJsonArray.forEach(::visit)
                return
            }
            if (!element.isJsonObject) return

            val obj = element.asJsonObject
            val contentType = firstString(obj, "content_type", "contentType", "mime_type")
            val directUrl = firstString(obj, "url", "video_url", "playback_url", "content_url", "contentUrl")
            if (directUrl != null && isPlayableXVideoUrl(directUrl, contentType)) {
                mediaUrls += directUrl
            }

            if (thumbnailUrl == null) {
                thumbnailUrl = firstString(obj, "media_url_https", "thumbnail_url", "poster_url", "media_url")
                    ?.takeIf(::isHttpUrl)
            }

            if (durationMs == null) {
                durationMs = firstString(obj, "duration_millis", "duration_ms")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            }

            obj.entrySet().forEach { (_, child) ->
                if (child.isJsonObject || child.isJsonArray) visit(child)
            }
        }

        visit(root)
        if (mediaUrls.isEmpty()) return null

        return ParsedPageMedia(
            title = title?.trim()?.takeIf(String::isNotBlank)?.take(160),
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs,
            mediaUrls = mediaUrls.take(MAX_SYNDICATION_MEDIA_URLS),
        )
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            val value = obj.get(key) ?: return@firstNotNullOfOrNull null
            if (!value.isJsonPrimitive || value.asJsonPrimitive.isBoolean) return@firstNotNullOfOrNull null
            runCatching { value.asString.trim().takeIf(String::isNotBlank) }.getOrNull()
        }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun isPlayableXVideoUrl(rawUrl: String, contentType: String?): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "twimg.com" && !host.endsWith(".twimg.com")) return false

        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        if (extension in IMAGE_EXTENSIONS) return false
        if (extension !in VIDEO_EXTENSIONS) return false

        val mime = contentType.orEmpty().lowercase()
        if (mime.startsWith("image/") || mime.startsWith("audio/")) return false
        return mime.isBlank() || mime.startsWith("video/") ||
            mime.contains("mpegurl") || mime.contains("dash+xml") ||
            mime == "application/octet-stream"
    }

    private companion object {
        const val X_SOCIAL_RESOLVE_TIMEOUT_MS = 28_000L
        const val X_SYNDICATION_FETCH_TIMEOUT_MS = 4_000L
        const val SYNDICATION_ENDPOINT = "https://cdn.syndication.twimg.com/tweet-result"
        const val X_REFERER = "https://x.com/"
        const val MAX_SYNDICATION_MEDIA_URLS = 32
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "m3u8", "mpd")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
    }
}
