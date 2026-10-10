package com.ahdownload.feature.home

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaPresentationModel
import java.net.URI
import java.util.Locale

/**
 * Records the complete data path for the Smart Center card: raw sources, normalized
 * options, why sources are/aren't actionable, user choices, validation and download state.
 * Source URLs and request-header values are deliberately never written to the trace.
 */
class ResultCardTraceRecorder(
    private val logger: UiTraceLogger,
    private val generation: String,
) {
    fun recordSnapshot(
        platform: String?,
        kind: MediaKind?,
        title: String,
        hasThumbnail: Boolean,
        durationMs: Long?,
        rawCandidates: List<MediaCandidate>,
        primaryOptions: List<MediaPresentationModel>,
        videoOptions: List<MediaPresentationModel>,
        audioOptions: List<MediaPresentationModel>,
        directAudioAvailable: Boolean,
        audioAvailable: Boolean,
        visibleVideoOptions: Int,
        videoOptionsExpanded: Boolean,
        selectedCandidateId: String?,
        selectedAudioCandidateId: String?,
        selectedAudioOutputFormat: AudioOutputFormat?,
    ) {
        val videoIds = videoOptions.mapTo(mutableSetOf()) { it.candidate.id }
        val audioIds = audioOptions.mapTo(mutableSetOf()) { it.candidate.id }
        val primaryIds = primaryOptions.mapTo(mutableSetOf()) { it.candidate.id }
        val rawVideo = rawCandidates.filter { it.format.kind == MediaKind.Video && it.format.hasVideo }
        val rawAudio = rawCandidates.filter { it.format.kind == MediaKind.Audio && it.format.hasAudio }
        val knownResolutions = videoOptions.mapNotNull {
            it.candidate.format.height?.takeIf { height -> height > 0 }
        }.distinct().size

        record(
            component = "result_card",
            event = "RESULT_SNAPSHOT",
            state = if (videoOptions.isNotEmpty() || audioAvailable) "READY" else "INCOMPLETE",
            context = mapOf(
                "platform" to (platform ?: "unknown"),
                "media_kind" to (kind?.name ?: "UNKNOWN"),
                "title_present" to title.isNotBlank().toString(),
                "title_length" to title.length.toString(),
                "thumbnail_present" to hasThumbnail.toString(),
                "duration_ms" to (durationMs?.toString() ?: "unknown"),
                "raw_candidate_count" to rawCandidates.size.toString(),
                "deduplicated_primary_option_count" to primaryOptions.size.toString(),
                "raw_video_source_count" to rawVideo.size.toString(),
                "raw_audio_source_count" to rawAudio.size.toString(),
                "video_picker_option_count" to videoOptions.size.toString(),
                "video_picker_visible_count" to visibleVideoOptions.toString(),
                "audio_source_option_count" to audioOptions.size.toString(),
                "audio_extraction_available" to audioAvailable.toString(),
                "direct_audio_source_available" to directAudioAvailable.toString(),
                "audio_output_format_count" to AudioOutputFormat.entries.size.toString(),
                "known_video_resolution_count" to knownResolutions.toString(),
                "unknown_video_quality_count" to videoOptions.count {
                    (it.candidate.format.height ?: 0) <= 0
                }.toString(),
                "video_only_fallback_option_count" to videoOptions.count {
                    it.candidate.format.kind == MediaKind.Video &&
                        it.candidate.format.hasVideo &&
                        !it.candidate.format.hasAudio &&
                        !directAudioAvailable &&
                        it.candidate.id != "direct"
                }.toString(),
                "unresolved_video_source_count" to rawCandidates.count {
                    it.format.kind == MediaKind.Video && it.format.hasVideo &&
                        !it.format.hasAudio && !directAudioAvailable
                }.toString(),
                "confirmed_muxed_video_count" to rawCandidates.count {
                    it.format.kind == MediaKind.Video && it.format.hasVideo && it.format.hasAudio
                }.toString(),
                "browser_observed_source_count" to rawCandidates.count {
                    it.sourceContext.name == "BROWSER_OBSERVED"
                }.toString(),
                "best_overall_id" to safeId(primaryOptions.firstOrNull {
                    it.recommendation.name == "BestOverall"
                }?.candidate?.id),
                "best_quality_id" to safeId(primaryOptions.firstOrNull {
                    it.recommendation.name == "BestQuality"
                }?.candidate?.id),
                "smallest_size_id" to safeId(primaryOptions.firstOrNull {
                    it.recommendation.name == "SmallestSize"
                }?.candidate?.id),
                "no_auto_selection" to (
                    selectedCandidateId == null &&
                        selectedAudioCandidateId == null &&
                        selectedAudioOutputFormat == null
                    ).toString(),
            ),
        )

        rawCandidates.forEachIndexed { index, candidate ->
            val format = candidate.format
            val actionableState = when {
                candidate.id in videoIds &&
                    format.kind == MediaKind.Video && format.hasVideo &&
                    !format.hasAudio && !directAudioAvailable && candidate.id != "direct" ->
                    "VIDEO_ONLY_FALLBACK_OPTION"
                candidate.id in videoIds -> "VIDEO_PICKER_OPTION"
                format.kind == MediaKind.Video && format.hasVideo &&
                    !format.hasAudio && !directAudioAvailable -> "BLOCKED_AUDIO_TRACK_UNCONFIRMED"
                candidate.id in audioIds -> "AUDIO_SOURCE_OPTION"
                candidate.id in primaryIds -> "PRIMARY_RESULT_OTHER_OR_HIDDEN"
                format.kind == MediaKind.Video && !format.hasVideo -> "BLOCKED_VIDEO_TRACK_MISSING"
                else -> "DEDUPLICATED_OR_FILTERED"
            }
            record(
                component = "source_candidate",
                event = "SOURCE_CANDIDATE",
                state = actionableState,
                context = mapOf(
                    "candidate_index" to (index + 1).toString(),
                    "candidate_id" to safeId(candidate.id),
                    "format_id" to safeId(format.id),
                    "source_context" to candidate.sourceContext.name,
                    "source_host" to hostOnly(candidate.sourceUrl),
                    "media_kind" to format.kind.name,
                    "container" to format.container.name,
                    "width" to (format.width?.toString() ?: "unknown"),
                    "height" to (format.height?.toString() ?: "unknown"),
                    "fps" to (format.fps?.let { "%.2f".format(Locale.US, it) } ?: "unknown"),
                    "bitrate_kbps" to (format.bitrateKbps?.toString() ?: "unknown"),
                    "file_size_bytes" to (format.fileSizeBytes?.toString() ?: "unknown"),
                    "video_codec" to safeLabel(format.videoCodec),
                    "audio_codec" to safeLabel(format.audioCodec),
                    "has_video" to format.hasVideo.toString(),
                    "has_audio" to format.hasAudio.toString(),
                    "request_header_count" to candidate.requestHeaders.size.toString(),
                    "session_cookie_host_present" to (!candidate.sessionCookieHost.isNullOrBlank()).toString(),
                    "companion_audio_present" to (!candidate.companionAudioSourceUrl.isNullOrBlank()).toString(),
                    "streaming_manifest" to candidate.streamingManifest.toString(),
                    "companion_audio_manifest" to candidate.companionAudioStreamingManifest.toString(),
                    "presented_as_primary_option" to (candidate.id in primaryIds).toString(),
                    "presented_in_video_picker" to (candidate.id in videoIds).toString(),
                    "presented_in_audio_picker" to (candidate.id in audioIds).toString(),
                ),
            )
        }

        (primaryOptions + audioOptions).distinctBy { it.candidate.id }.forEachIndexed { index, option ->
            val format = option.candidate.format
            record(
                component = "result_option",
                event = "RESULT_OPTION",
                state = if (option.candidate.id in videoIds || option.candidate.id in audioIds) {
                    "ACTIONABLE"
                } else {
                    "NOT_IN_ACTIVE_PICKER"
                },
                context = mapOf(
                    "option_index" to (index + 1).toString(),
                    "candidate_id" to safeId(option.candidate.id),
                    "format_id" to safeId(format.id),
                    "group" to option.group.name,
                    "quality_label" to safeLabel(option.qualityLabel),
                    "codec_label" to safeLabel(option.codecLabel),
                    "container" to format.container.name,
                    "width" to (format.width?.toString() ?: "unknown"),
                    "height" to (format.height?.toString() ?: "unknown"),
                    "fps" to (format.fps?.let { "%.2f".format(Locale.US, it) } ?: "unknown"),
                    "bitrate_kbps" to (format.bitrateKbps?.toString() ?: "unknown"),
                    "file_size_bytes" to (format.fileSizeBytes?.toString() ?: "unknown"),
                    "has_video" to format.hasVideo.toString(),
                    "has_audio" to format.hasAudio.toString(),
                    "source_state" to option.sourceState.name,
                    "recommendation" to option.recommendation.name,
                    "score" to option.score.toString(),
                    "visible_in_video_picker" to (option.candidate.id in videoIds).toString(),
                    "visible_in_audio_picker" to (option.candidate.id in audioIds).toString(),
                    "video_options_expanded" to videoOptionsExpanded.toString(),
                ),
            )
        }
    }

    fun recordState(
        selectionMode: String,
        selectedVideoId: String?,
        selectedAudioId: String?,
        selectedAudioOutputFormat: String?,
        selectedVideoQuality: String?,
        selectedAudioQuality: String?,
        validatingCandidateId: String?,
        downloadQueued: Boolean,
        favorite: Boolean,
        videoOptionsExpanded: Boolean,
        visibleVideoOptions: Int,
        canDownload: Boolean,
        errorMessage: String?,
    ) {
        val state = when {
            !errorMessage.isNullOrBlank() -> "ERROR"
            downloadQueued -> "QUEUED"
            validatingCandidateId != null -> "VALIDATING"
            selectedVideoId != null || selectedAudioId != null || selectedAudioOutputFormat != null -> "OPTION_SELECTED"
            else -> "IDLE"
        }
        val context = mapOf(
            "selection_mode" to selectionMode,
            "selected_video_candidate_id" to safeId(selectedVideoId),
            "selected_audio_candidate_id" to safeId(selectedAudioId),
            "selected_video_quality" to safeLabel(selectedVideoQuality),
            "selected_audio_quality" to safeLabel(selectedAudioQuality),
            "selected_audio_output_format" to (selectedAudioOutputFormat ?: "none"),
            "validating_candidate_id" to safeId(validatingCandidateId),
            "download_queued" to downloadQueued.toString(),
            "favorite" to favorite.toString(),
            "video_options_expanded" to videoOptionsExpanded.toString(),
            "visible_video_options" to visibleVideoOptions.toString(),
            "error_present" to (!errorMessage.isNullOrBlank()).toString(),
            "error_category" to classifyError(errorMessage),
            "error_summary" to sanitizeError(errorMessage),
            "can_download" to canDownload.toString(),
        )
        record(
            component = "card_state",
            event = "CARD_STATE",
            state = state,
            context = context,
            level = if (state == "ERROR") DiagnosticLevel.ERROR else DiagnosticLevel.INFO,
        )
        if (state == "ERROR") {
            record(
                component = "card_error",
                event = "RESULT_CARD_ERROR",
                state = "ERROR",
                context = context,
                level = DiagnosticLevel.ERROR,
            )
        }
    }

    fun recordAction(action: String, component: String, context: Map<String, String> = emptyMap()) {
        record(
            component = component,
            event = "USER_ACTION",
            state = "TRIGGERED",
            context = mapOf("action" to action) + context.mapValues { (key, value) ->
                if (key.contains("candidate_id")) safeId(value) else safeLabel(value)
            },
        )
    }

    private fun record(
        component: String,
        event: String,
        state: String,
        context: Map<String, String>,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
    ) {
        logger.record(
            screen = "RESULT_CARD",
            component = component,
            event = event,
            state = state,
            context = mapOf("result_generation" to generation) + context,
            level = level,
        )
    }

    private fun safeId(value: String?): String =
        value?.takeIf { SAFE_ID.matches(it) } ?: "none_or_redacted"

    private fun safeLabel(value: String?): String =
        value.orEmpty().filter { it.isLetterOrDigit() || it in "._- /·" }.take(80).ifBlank { "unknown" }

    private fun hostOnly(url: String): String =
        runCatching { URI(url).host?.lowercase(Locale.US) }
            .getOrNull()
            ?.takeIf { HOST.matches(it) }
            ?: "unknown"

    private fun classifyError(message: String?): String {
        if (message.isNullOrBlank()) return "none"
        return when {
            Regex("(?i)http\\s*[_-]?403|\\b403\\b|forbidden").containsMatchIn(message) -> "HTTP_403"
            Regex("(?i)http\\s*[_-]?401|\\b401\\b|unauthorized|authentication").containsMatchIn(message) -> "AUTH_REQUIRED"
            Regex("(?i)timeout|timed out|انتهت المهلة").containsMatchIn(message) -> "TIMEOUT"
            Regex("(?i)network|socket|connection|dns|شبك").containsMatchIn(message) -> "NETWORK"
            Regex("(?i)validation|invalid media|تحقق|مصدر غير صالح").containsMatchIn(message) -> "MEDIA_VALIDATION"
            else -> "UNCLASSIFIED"
        }
    }

    private fun sanitizeError(message: String?): String {
        if (message.isNullOrBlank()) return "none"
        val withoutUrls = URL_PATTERN.replace(message, "[URL_REDACTED]")
        val withoutSecrets = SECRET_PATTERN.replace(withoutUrls, "[PRIVATE_VALUE_REDACTED]")
        return withoutSecrets.replace(Regex("[\\r\\n\\t]+"), " ").take(240)
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_.:-]{1,80}")
        private val HOST = Regex("[A-Za-z0-9.-]{1,160}")
        private val URL_PATTERN = Regex("(?i)https?://[^\\s]+")
        private val SECRET_PATTERN = Regex(
            "(?i)(cookie|token|authorization|password|passwd|secret|signature|sig|api[_-]?key)(\\s*[:=]\\s*)[^\\s&;,]+",
        )
    }
}
