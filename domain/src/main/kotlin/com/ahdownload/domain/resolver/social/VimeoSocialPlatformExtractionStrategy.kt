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

/** Reads Vimeo's public player config when public HTML omits progressive media URLs. */
internal class VimeoSocialPlatformExtractionStrategy :
    StandardSocialPlatformExtractionStrategy(MediaPlatform.Vimeo) {
    override val additionalMediaMetricName: String = "vimeo_player_config_media_count"

    override fun effectiveTimeoutMillis(configuredTimeoutMs: Long): Long =
        if (configuredTimeoutMs == SOCIAL_RESOLVE_TIMEOUT_MS) PUBLIC_METADATA_RESOLVE_TIMEOUT_MS
        else configuredTimeoutMs

    override suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? {
        if (pageUrl.isNullOrBlank() || browserMediaCount > 0) return null
        val videoId = extractVideoId(pageUrl) ?: return null
        val endpoint = "https://player.vimeo.com/video/$videoId/config"
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
        val media = parsed?.mediaUrls.orEmpty().filter(::isVimeoMediaUrl)
        val status = when {
            exceptionType != null -> "request_failed"
            body == null -> "request_timeout"
            media.isNotEmpty() -> "media_found"
            else -> "no_media"
        }
        logger.log(
            if (media.isNotEmpty()) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SOCIAL_VIMEO_PLAYER_CONFIG_RESULT",
            "اكتمل فحص إعدادات المشغل العامة في Vimeo",
            "social.resolve.vimeo_config",
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
        if (!isVimeoMediaUrl(sourceUrl)) return safeHeaders
        if (safeHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) return safeHeaders
        return safeHeaders + ("Referer" to "https://player.vimeo.com/")
    }

    private fun extractVideoId(pageUrl: String): String? {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "vimeo.com" && !host.endsWith(".vimeo.com")) return null
        return uri.path.orEmpty().split('/').firstOrNull { it.matches(Regex("[0-9]{4,20}")) }
    }

    private fun isVimeoMediaUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val trustedHost = host == "vimeo.com" || host.endsWith(".vimeo.com") ||
            host.endsWith(".vimeocdn.com") || host.endsWith(".akamaized.net")
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        return trustedHost && extension in setOf("mp4", "m3u8", "mpd", "webm", "mov")
    }

    private companion object {
        const val PUBLIC_METADATA_RESOLVE_TIMEOUT_MS = 28_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
