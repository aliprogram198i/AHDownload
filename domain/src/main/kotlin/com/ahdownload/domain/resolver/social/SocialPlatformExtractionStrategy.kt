package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.ParsedPageMedia

/**
 * Platform-specific extraction policy plugged into the shared social-media pipeline.
 * Fallbacks and request context are platform-specific; orchestration and validation stay shared.
 */
interface SocialPlatformExtractionStrategy {
    val platform: MediaPlatform
    val additionalMediaMetricName: String
        get() = "platform_fallback_media_count"

    fun effectiveTimeoutMillis(configuredTimeoutMs: Long): Long = configuredTimeoutMs
    fun sessionInspectionStatus(session: BrowserMediaSession): String = "not_applicable"

    suspend fun extractAdditionalMedia(
        pageUrl: String?,
        browserMediaCount: Int,
        pageFallbackMediaCount: Int,
        pageClient: HttpTextClient,
        operationId: String?,
        logger: DiagnosticLogger,
    ): ParsedPageMedia? = null

    fun shouldKeepCandidate(requestedKind: MediaKind, candidateKind: MediaKind): Boolean =
        !(requestedKind == MediaKind.Video && candidateKind == MediaKind.Image)

    fun requestHeadersForCandidate(sourceUrl: String, safeHeaders: Map<String, String>): Map<String, String> =
        safeHeaders
}

/** Each social platform receives a concrete policy object instead of embedding platform branches in the engine. */
internal fun socialPlatformExtractionStrategy(platform: MediaPlatform): SocialPlatformExtractionStrategy =
    when (platform) {
        MediaPlatform.Instagram -> InstagramSocialPlatformExtractionStrategy()
        MediaPlatform.Facebook -> FacebookSocialPlatformExtractionStrategy()
        MediaPlatform.TikTok -> TikTokSocialPlatformExtractionStrategy()
        MediaPlatform.X -> XSocialPlatformExtractionStrategy()
        MediaPlatform.Snapchat -> SnapchatSocialPlatformExtractionStrategy()
        MediaPlatform.Pinterest -> PinterestSocialPlatformExtractionStrategy()
        MediaPlatform.Reddit -> RedditSocialPlatformExtractionStrategy()
        MediaPlatform.Twitch -> TwitchSocialPlatformExtractionStrategy()
        MediaPlatform.Vimeo -> VimeoSocialPlatformExtractionStrategy()
        else -> StandardSocialPlatformExtractionStrategy(platform)
    }
