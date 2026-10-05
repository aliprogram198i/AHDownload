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
import com.ahdownload.domain.validation.CandidateValidationResult
import com.ahdownload.domain.validation.CandidateValidator
import com.ahdownload.domain.validation.OkHttpMediaProbe

class HomeResolver(
    logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    sessionProvider: com.ahdownload.domain.resolver.youtube.YouTubeSessionProvider? = null,
    private val youtubeResolver: YouTubeResolver = YouTubeResolver(
        httpClient = OkHttpTextClient(),
        logger = logger,
        sessionProvider = sessionProvider,
    ),
    private val candidateValidator: CandidateValidator = CandidateValidator(OkHttpMediaProbe()),
    private val candidateRanker: CandidateRanker = CandidateRanker(),
) {
    suspend fun resolve(link: MediaLink, operationId: String? = null): ResolverResult {
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
            MediaPlatform.YouTube -> youtubeResolver.resolve(ResolverRequest(link, operationId = operationId))
            else -> ResolverResult.Failure(
                com.ahdownload.domain.resolver.FailureCode.UnsupportedPlatform,
                "المنصة غير موصولة بعد.",
            )
        }

        return when (result) {
            is ResolverResult.Success -> result.copy(candidates = candidateRanker.rank(result.candidates))
            is ResolverResult.Failure -> result
        }
    }

    suspend fun validate(candidate: MediaCandidate): CandidateValidationResult =
        candidateValidator.validate(candidate)

    private fun containerFor(kind: MediaKind, url: String): MediaContainer {
        val extension = url.substringBefore('?').substringAfterLast('.').lowercase()
        return if (kind == MediaKind.Audio) {
            when (extension) {
                "m4a" -> MediaContainer.M4a
                "mp3" -> MediaContainer.Mp3
                "aac" -> MediaContainer.Aac
                "ogg" -> MediaContainer.Ogg
                "flac" -> MediaContainer.Flac
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
