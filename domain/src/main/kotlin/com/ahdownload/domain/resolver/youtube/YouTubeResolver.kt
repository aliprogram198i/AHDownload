package com.ahdownload.domain.resolver.youtube

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
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
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
) : PlatformAdapter {
    private val playerClient = YouTubePlayerClient(httpClient)

    override val capability = com.ahdownload.domain.resolver.ResolverCapability(
        platform = MediaPlatform.YouTube,
        supportedKinds = setOf(MediaKind.Video, MediaKind.Audio),
    )

    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        if (request.link.platform != MediaPlatform.YouTube) {
            return ResolverResult.Failure(FailureCode.UnsupportedPlatform, "الرابط ليس YouTube.")
        }

        val videoId = extractVideoId(request.link.normalizedUrl)
            ?: return failure(FailureCode.ResolverUnavailable, "تعذر تحديد معرف فيديو YouTube.")

        return try {
            val html = httpClient.get(request.link.normalizedUrl)
            val direct = parser.parse(html)
            if (direct is ResolverResult.Success) return filterKind(direct, request)
            val apiResponse = runCatching {
                playerClient.fetchPlayerResponse(html, request.link.normalizedUrl)
            }.getOrNull()

            if (apiResponse != null) {
                val apiResult = parser.parsePlayerResponse(apiResponse)
                if (apiResult is ResolverResult.Success) return filterKind(apiResult, request)
                logger.log(
                    DiagnosticLevel.WARNING,
                    type = "youtube_player_no_candidates",
                    reason = (apiResult as ResolverResult.Failure).message ?: apiResult.code.name,
                    operation = "youtube.resolve",
                    context = mapOf("video_id" to videoId, "fallback" to "youtubei_player"),
                    throwable = null,
                )
                return apiResult
            }

            failure(
                direct.failureCodeOr(FailureCode.ResolverUnavailable),
                direct.failureMessageOr("تعذر استخراج الوسائط من استجابة YouTube."),
                context = mapOf("video_id" to videoId, "fallback" to "youtubei_player_unavailable"),
            )
        } catch (error: Exception) {
            failure(
                FailureCode.ResolverUnavailable,
                "فشل اتصال YouTube: " + (error.message ?: error::class.simpleName.orEmpty()),
                error,
                mapOf("video_id" to videoId),
            )
        }
    }

    private fun filterKind(result: ResolverResult.Success, request: ResolverRequest): ResolverResult {
        val candidates = request.requestedKind?.let { kind ->
            result.candidates.filter { it.format.kind == kind }
        } ?: result.candidates
        return if (candidates.isEmpty()) {
            failure(FailureCode.NoCandidates, "لا توجد صيغة مطابقة لنوع الوسائط المطلوب.")
        } else {
            result.copy(candidates = candidates)
        }
    }

    private fun failure(
        code: FailureCode,
        reason: String,
        error: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    ): ResolverResult.Failure {
        logger.log(
            DiagnosticLevel.ERROR,
            type = code.name,
            reason = reason,
            operation = "youtube.resolve",
            context = context,
            throwable = error,
        )
        return ResolverResult.Failure(code, reason)
    }

    private fun extractVideoId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "youtu.be") return uri.path.trim('/').substringBefore('/').takeIf { it.length >= 6 }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val queryId = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
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

    private fun ResolverResult.failureCodeOr(default: FailureCode): FailureCode =
        (this as? ResolverResult.Failure)?.code ?: default

    private fun ResolverResult.failureMessageOr(default: String): String =
        (this as? ResolverResult.Failure)?.message ?: default
}
