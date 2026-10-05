package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.PlatformAdapter
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import java.net.URI

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
            return ResolverResult.Failure(FailureCode.UnsupportedPlatform, "الرابط ليس YouTube.")
        }

        val videoId = extractVideoId(request.link.normalizedUrl)
            ?: return ResolverResult.Failure(FailureCode.ResolverUnavailable, "تعذر تحديد معرف فيديو YouTube.")

        var firstFailure: ResolverResult.Failure? = null
        val urls = listOf(
            request.link.normalizedUrl,
            "https://www.youtube-nocookie.com/embed/$videoId?hl=en&autoplay=0",
        )

        for ((index, url) in urls.withIndex()) {
            try {
                val parsed = parser.parse(httpClient.get(url))
                when (parsed) {
                    is ResolverResult.Success -> {
                        val candidates = request.requestedKind
                            ?.let { kind -> parsed.candidates.filter { it.format.kind == kind } }
                            ?: parsed.candidates

                        if (candidates.isNotEmpty()) {
                            return parsed.copy(candidates = candidates)
                        }
                        firstFailure = ResolverResult.Failure(
                            FailureCode.NoCandidates,
                            "YouTube أعاد استجابة دون صيغ قابلة للتنزيل.",
                        )
                    }
                    is ResolverResult.Failure -> {
                        firstFailure = parsed
                        if (index == urls.lastIndex) return parsed
                    }
                }
            } catch (error: Exception) {
                firstFailure = ResolverResult.Failure(
                    FailureCode.ResolverUnavailable,
                    "فشل اتصال YouTube: " + (error.message ?: error::class.simpleName.orEmpty()),
                )
            }
        }

        return firstFailure ?: ResolverResult.Failure(
            FailureCode.ResolverUnavailable,
            "تعذر استخراج الوسائط من YouTube.",
        )
    }

    private fun extractVideoId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "youtu.be") {
            return uri.path.trim('/').substringBefore('/').takeIf { it.length >= 6 }
        }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val queryId = uri.rawQuery.orEmpty()
                .split('&')
                .firstNotNullOfOrNull { part ->
                    val pieces = part.split('=', limit = 2)
                    if (pieces.size == 2 && pieces[0] == "v") pieces[1] else null
                }
            if (queryId != null) return queryId
            val segments = uri.path.trim('/').split('/')
            val markerIndex = segments.indexOfFirst { it == "shorts" || it == "embed" || it == "live" }
            if (markerIndex >= 0) return segments.getOrNull(markerIndex + 1)
        }
        return null
    }
}
