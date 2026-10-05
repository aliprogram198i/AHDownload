package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.PlatformAdapter
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult

class YouTubeResolver(
    private val httpClient: HttpTextClient,
    private val parser: YouTubePlayerResponseParser = YouTubePlayerResponseParser(),
) : PlatformAdapter {
    override val capability = com.ahdownload.domain.resolver.ResolverCapability(
        platform = MediaPlatform.YouTube,
        supportedKinds = setOf(MediaKind.Video, MediaKind.Audio),
    )

    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        if (request.link.platform != MediaPlatform.YouTube) {
            return ResolverResult.Failure(FailureCode.UnsupportedPlatform)
        }

        return runCatching {
            when (val result = parser.parse(httpClient.get(request.link.normalizedUrl))) {
                is ResolverResult.Success -> {
                    val candidates = request.requestedKind
                        ?.let { kind -> result.candidates.filter { it.format.kind == kind } }
                        ?: result.candidates

                    if (candidates.isEmpty()) {
                        ResolverResult.Failure(FailureCode.NoCandidates)
                    } else {
                        result.copy(candidates = candidates)
                    }
                }
                is ResolverResult.Failure -> result
            }
        }.getOrElse {
            ResolverResult.Failure(FailureCode.ResolverUnavailable)
        }
    }
}
