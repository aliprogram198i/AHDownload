package com.ahdownload.feature.home

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.CandidateRanker
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.OkHttpTextClient
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.resolver.youtube.YouTubeResolver
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import com.ahdownload.domain.resolver.social.*
import com.ahdownload.domain.validation.CandidateValidationResult
import com.ahdownload.domain.validation.CandidateValidator
import com.ahdownload.domain.validation.OkHttpMediaProbe

class HomeResolver(
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    sessionProvider: com.ahdownload.domain.resolver.youtube.YouTubeSessionProvider? = null,
    private val youtubeResolver: YouTubeResolver = YouTubeResolver(
        httpClient = OkHttpTextClient(),
        logger = logger,
        sessionProvider = sessionProvider,
    ),
    private val candidateValidator: CandidateValidator = CandidateValidator(
        probe = OkHttpMediaProbe(logger = logger),
        logger = logger,
    ),
    private val candidateRanker: CandidateRanker = CandidateRanker(),
    private val browserMediaSessionProvider: BrowserMediaSessionProvider? = null,
) {
    suspend fun resolve(
        link: MediaLink,
        operationId: String? = null,
        forceYouTubeFallbacks: Boolean = false,
    ): ResolverResult {
        if (link.platform == MediaPlatform.DirectMedia && link.kind != MediaKind.Unknown) {
            return ResolverResult.Success(
                title = link.normalizedUrl.substringAfterLast('/').substringBefore('?').ifBlank { null },
                thumbnailUrl = null,
                durationMs = null,
                candidates = listOf(
                    MediaCandidate(
                        id = "direct",
                        sourceUrl = link.normalizedUrl,
                        format = MediaFormat(
                            id = "direct",
                            kind = link.kind,
                            container = containerFor(link.kind, link.normalizedUrl),
                            hasVideo = link.kind == MediaKind.Video,
                            hasAudio = link.kind == MediaKind.Audio,
                        ),
                    ),
                ),
            )
        }

        val result = when (link.platform) {
            MediaPlatform.YouTube -> {
                val request = ResolverRequest(link, operationId = operationId)
                if (forceYouTubeFallbacks) {
                    youtubeResolver.resolveWithFallbacks(request)
                } else {
                    youtubeResolver.resolve(request)
                }
            }
            MediaPlatform.Instagram,
            MediaPlatform.Facebook,
            MediaPlatform.TikTok,
            MediaPlatform.X,
            MediaPlatform.Snapchat,
            MediaPlatform.Pinterest,
            MediaPlatform.Reddit,
            MediaPlatform.Twitch,
            MediaPlatform.Vimeo -> resolveSocial(link, operationId)
            else -> ResolverResult.Failure(
                com.ahdownload.domain.resolver.FailureCode.UnsupportedPlatform,
                "المنصة غير مدعومة في المحرك الحالي.",
            )
        }

        return when (result) {
            is ResolverResult.Success -> result.copy(candidates = candidateRanker.rank(result.candidates))
            is ResolverResult.Failure -> result
        }
    }

    private suspend fun resolveSocial(link: MediaLink, operationId: String?): ResolverResult {
        val provider = browserMediaSessionProvider ?: return ResolverResult.Failure(
            com.ahdownload.domain.resolver.FailureCode.ResolverUnavailable,
            "محرك تصفح الوسائط غير متاح في هذا الإصدار.",
        )
        val request = ResolverRequest(link, operationId = operationId)

        // Each social platform is routed to its own fixed-identity adapter.
        return when (link.platform) {
            MediaPlatform.Instagram -> InstagramResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.Facebook -> FacebookResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.TikTok -> TikTokResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.X -> XResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.Snapchat -> SnapchatResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.Pinterest -> PinterestResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.Reddit -> RedditResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.Twitch -> TwitchResolverAdapter(provider, logger).resolve(request)
            MediaPlatform.Vimeo -> VimeoResolverAdapter(provider, logger).resolve(request)
            else -> ResolverResult.Failure(
                com.ahdownload.domain.resolver.FailureCode.UnsupportedPlatform,
                "المنصة غير مدعومة في محرك التواصل الاجتماعي.",
            )
        }
    }

    suspend fun validate(candidate: MediaCandidate, operationId: String? = null): CandidateValidationResult =
        candidateValidator.validate(candidate, operationId)

    private fun containerFor(kind: MediaKind, url: String): MediaContainer {
        val extension = url.substringBefore('?').substringAfterLast('.').lowercase()
        return if (kind == MediaKind.Audio) {
            when (extension) {
                "m4a" -> MediaContainer.M4a
                "mp3" -> MediaContainer.Mp3
                "aac" -> MediaContainer.Aac
                "ogg" -> MediaContainer.Ogg
                "flac" -> MediaContainer.Flac
                "wav" -> MediaContainer.Wav
                else -> MediaContainer.Unknown
            }
        } else {
            when (extension) {
                "mp4", "m4v" -> MediaContainer.Mp4
                "webm" -> MediaContainer.Webm
                "mkv" -> MediaContainer.Mkv
                "mov" -> MediaContainer.Mov
                else -> MediaContainer.Unknown
            }
        }
    }
}
