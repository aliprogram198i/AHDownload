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

/** Public Reddit post JSON often exposes the v.redd.it fallback absent from the HTML shell. */
internal class RedditSocialPlatformExtractionStrategy :
    StandardSocialPlatformExtractionStrategy(MediaPlatform.Reddit) {
    override val additionalMediaMetricName: String = "reddit_json_media_count"

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        if (pageUrl.isNullOrBlank() || browserMediaCount > 0) return null
        val postId = extractPostId(pageUrl) ?: return null
        val endpoint = "https://www.reddit.com/comments/$postId.json?raw_json=1"
        var body: String? = null
        var exceptionType: String? = null
        try {
            body = withTimeoutOrNull(4_500L) {
                pageClient.get(
                    endpoint,
                    mapOf(
                        "Accept" to "application/json",
                        "Accept-Language" to "en-US,en;q=0.8",
                        "Referer" to pageUrl,
                        "User-Agent" to USER_AGENT,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            exceptionType = error::class.java.simpleName
        }

        val parsed = body?.let { WebPageMediaParser.parse(it, pageUrl) }
        val media = parsed?.mediaUrls.orEmpty().filter(::isRedditVideoUrl)
        val status = when {
            exceptionType != null -> "request_failed"
            body == null -> "request_timeout"
            media.isNotEmpty() -> "media_found"
            else -> "no_media"
        }
        logger.log(
            if (media.isNotEmpty()) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SOCIAL_REDDIT_JSON_FALLBACK_RESULT",
            "اكتمل فحص بيانات المنشور العامة في Reddit",
            "social.resolve.reddit_json",
            buildMap {
                put("platform", platform.name)
                put("operation_id", operationId ?: "none")
                put("fallback_status", status)
                put("response_chars", (body?.length ?: 0).toString())
                put("media_count", media.size.toString())
                exceptionType?.let { put("exception_type", it) }
            },
            null,
        )
        return parsed?.copy(mediaUrls = media)?.takeIf { it.mediaUrls.isNotEmpty() }
    }

    override fun requestHeadersForCandidate(
        sourceUrl: String,
        safeHeaders: Map<String, String>,
    ): Map<String, String> {
        if (!isRedditVideoUrl(sourceUrl)) return safeHeaders
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        return safeHeaders + ("Referer" to "https://www.reddit.com/")
    }

    private fun extractPostId(pageUrl: String): String? {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "reddit.com" && !host.endsWith(".reddit.com")) return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val index = segments.indexOfFirst { it.equals("comments", ignoreCase = true) }
        return segments.getOrNull(index + 1)
            ?.takeIf { index >= 0 && it.matches(Regex("[A-Za-z0-9]{5,12}")) }
    }

    private fun isRedditVideoUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        return (host == "v.redd.it" || host.endsWith(".redd.it") || host.endsWith(".redditmedia.com")) &&
            extension in setOf("mp4", "webm", "m3u8", "mpd")
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
