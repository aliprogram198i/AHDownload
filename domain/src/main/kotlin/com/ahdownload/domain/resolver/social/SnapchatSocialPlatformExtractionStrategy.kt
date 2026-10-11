package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Snapchat-specific extraction for public Spotlight pages.
 *
 * Spotlight exposes its media in Next.js page data rather than reliably exposing a
 * file extension in the HTML. We read only the contentUrl attached to the requested
 * story's videoMetadata and accept it only from Snapchat's media CDN.
 */
internal class SnapchatSocialPlatformExtractionStrategy :
    StandardSocialPlatformExtractionStrategy(MediaPlatform.Snapchat) {

    override val additionalMediaMetricName: String = "snapchat_spotlight_media_count"

    override fun effectiveTimeoutMillis(configuredTimeoutMs: Long): Long =
        if (configuredTimeoutMs == SOCIAL_RESOLVE_TIMEOUT_MS) SNAPCHAT_RESOLVE_TIMEOUT_MS
        else configuredTimeoutMs

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        // Keep browser-observed direct media and common HTML extraction as the primary paths.
        if (browserMediaCount > 0 || pageFallbackMediaCount > 0) return null

        val spotlightId = extractSpotlightId(pageUrl) ?: return null
        val safePageUrl = pageUrl ?: return null
        var html: String? = null
        var exceptionType: String? = null

        try {
            html = withTimeoutOrNull(SNAPCHAT_PAGE_FETCH_TIMEOUT_MS) {
                pageClient.get(
                    safePageUrl,
                    mapOf(
                        "Accept" to "text/html,application/xhtml+xml",
                        "Accept-Language" to "en-US,en;q=0.8",
                        "Referer" to SNAPCHAT_ORIGIN,
                    ),
                    mapOf(
                        "platform" to MediaPlatform.Snapchat.name,
                        "stage" to "SOCIAL_SNAPCHAT_SPOTLIGHT",
                        "operation_id" to (operationId ?: "none"),
                        "diagnostic_operation" to "social.resolve.snapchat_spotlight",
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Do not log exception messages because HTTP exceptions can contain full URLs.
            exceptionType = error::class.java.simpleName
        }

        val parsed = html?.let { parseSpotlightPage(it, spotlightId) }
        val mediaFound = parsed?.mediaUrls?.isNotEmpty() == true
        val status = when {
            exceptionType != null -> "request_failed"
            html == null -> "request_timeout"
            mediaFound -> "media_found"
            else -> "no_media"
        }

        logger.log(
            if (mediaFound) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SOCIAL_SNAPCHAT_SPOTLIGHT_FALLBACK_RESULT",
            "اكتمل فحص بيانات فيديو Spotlight العامة",
            "social.resolve.snapchat_spotlight",
            buildMap {
                put("platform", MediaPlatform.Snapchat.name)
                put("operation_id", operationId ?: "none")
                put("fallback_status", status)
                put("response_chars", (html?.length ?: 0).toString())
                put("media_count", (parsed?.mediaUrls?.size ?: 0).toString())
                put("spotlight_id_present", "true")
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
        if (!isSnapchatCdnUrl(sourceUrl)) return safeHeaders
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        return safeHeaders + ("Referer" to SNAPCHAT_ORIGIN)
    }

    private fun extractSpotlightId(pageUrl: String?): String? {
        if (pageUrl.isNullOrBlank()) return null
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "snapchat.com" && !host.endsWith(".snapchat.com")) return null

        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val spotlightIndex = segments.indexOfLast { it.equals("spotlight", ignoreCase = true) }
        if (spotlightIndex < 0) return null
        return segments.getOrNull(spotlightIndex + 1)
            ?.takeIf { it.length in 8..128 && it.all { char -> char.isLetterOrDigit() || char == '_' || char == '-' } }
    }

    private fun parseSpotlightPage(html: String, expectedId: String): ParsedPageMedia? {
        val nextData = NEXT_DATA_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val root = runCatching { JsonParser.parseString(nextData) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null

        val props = childObject(childObject(root, "props"), "pageProps") ?: return null
        val feed = childObject(props, "spotlightFeed") ?: return null
        val stories = feed.get("spotlightStories")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val matchingStory = stories.firstOrNull { story ->
            val storyObject = childObject(story as? JsonObject, "story")
            val storyId = readString(childObject(storyObject, "storyId"), "value")
            storyId == expectedId
        } ?: return null

        val metadata = (matchingStory as? JsonObject)?.get("metadata") ?: return null
        val videoMetadata = findObjectWithKey(metadata, "videoMetadata") ?: return null
        val contentUrl = readString(videoMetadata, "contentUrl", "content_url")
            ?.takeIf(::isSnapchatVideoSource)
            ?: return null
        val thumbnailUrl = readString(videoMetadata, "thumbnailUrl", "thumbnail_url")
            ?.takeIf(::isSnapchatCdnUrl)

        val durationMs = readString(videoMetadata, "durationMs", "duration_ms")
            ?.toDoubleOrNull()
            ?.toLong()
            ?.takeIf { it > 0L }

        val title = readString(videoMetadata, "name", "title", "description")
            ?.take(160)

        return ParsedPageMedia(
            title = title,
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs,
            mediaUrls = listOf(contentUrl),
        )
    }

    private fun findObjectWithKey(element: JsonElement, key: String): JsonObject? {
        if (element.isJsonObject) {
            val objectValue = element.asJsonObject.get(key)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
            if (objectValue != null) return objectValue
            for ((_, child) in element.asJsonObject.entrySet()) {
                findObjectWithKey(child, key)?.let { return it }
            }
        } else if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                findObjectWithKey(child, key)?.let { return it }
            }
        }
        return null
    }

    private fun childObject(parent: JsonObject?, name: String): JsonObject? =
        parent?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun readString(obj: JsonObject?, vararg names: String): String? {
        for (name in names) {
            val value = obj?.get(name) ?: continue
            if (!value.isJsonPrimitive || value.asJsonPrimitive.isBoolean) continue
            val text = runCatching { value.asString.trim() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun isSnapchatVideoSource(rawUrl: String): Boolean {
        if (!isSnapchatCdnUrl(rawUrl)) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        if (extension in IMAGE_EXTENSIONS) return false
        return true
    }

    private fun isSnapchatCdnUrl(rawUrl: String): Boolean = runCatching {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase() ?: return@runCatching false
        uri.scheme?.lowercase() == "https" &&
            (host == "sc-cdn.net" || host.endsWith(".sc-cdn.net"))
    }.getOrDefault(false)

    private companion object {
        const val SNAPCHAT_ORIGIN = "https://www.snapchat.com/"
        const val SNAPCHAT_RESOLVE_TIMEOUT_MS = 30_000L
        const val SNAPCHAT_PAGE_FETCH_TIMEOUT_MS = 6_500L
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
        val NEXT_DATA_REGEX = Regex(
            """(?is)<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
        )
    }
}
