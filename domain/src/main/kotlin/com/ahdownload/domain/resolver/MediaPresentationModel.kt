package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind

enum class MediaResultGroup { Video, Audio, Other }
enum class MediaResultRecommendation { BestOverall, BestQuality, SmallestSize, None }
enum class MediaSourceState { Ready, RefreshRequired, Unavailable }

data class MediaPresentationModel(
    val candidate: MediaCandidate,
    val group: MediaResultGroup,
    val recommendation: MediaResultRecommendation = MediaResultRecommendation.None,
    val score: Int,
    val sourceState: MediaSourceState,
    val qualityLabel: String,
    val codecLabel: String?,
    val fpsLabel: String?,
    val sizeLabel: String?,
    val dedupeKey: String,
)

data class SmartResultSet(
    val all: List<MediaPresentationModel>,
    val visible: List<MediaPresentationModel>,
    val video: List<MediaPresentationModel>,
    val audio: List<MediaPresentationModel>,
    val other: List<MediaPresentationModel>,
    val bestOverall: MediaPresentationModel?,
    val bestQuality: MediaPresentationModel?,
    val smallestSize: MediaPresentationModel?,
) {
    val hiddenCount: Int get() = (all.size - visible.size).coerceAtLeast(0)

    /** A direct audio-only source that can be used for audio extraction or muxing. */
    val directAudioSourceAvailable: Boolean
        get() = audio.any {
            it.candidate.format.kind == MediaKind.Audio && it.candidate.format.hasAudio
        }

    /**
     * Video choices are actionable only when they contain audio or a separate,
     * valid audio source is available for the merge pipeline.
     */
    val videoWithAudioOptions: List<MediaPresentationModel>
        get() = video.filter { item ->
            val format = item.candidate.format
            format.kind == MediaKind.Video &&
                format.hasVideo &&
                (format.hasAudio || directAudioSourceAvailable)
        }

    val muxedVideoSourceAvailable: Boolean
        get() = video.any {
            val format = it.candidate.format
            format.kind == MediaKind.Video && format.hasVideo && format.hasAudio
        }

    val audioExtractionAvailable: Boolean
        get() = directAudioSourceAvailable || muxedVideoSourceAvailable

    /** Video sources omitted from the video+audio picker due to missing audio proof. */
    val unresolvedVideoCandidateCount: Int
        get() = video.count {
            val format = it.candidate.format
            format.kind == MediaKind.Video &&
                format.hasVideo &&
                !format.hasAudio &&
                !directAudioSourceAvailable
        }
}

internal fun MediaCandidate.resultGroup(): MediaResultGroup = when (format.kind) {
    MediaKind.Video -> MediaResultGroup.Video
    MediaKind.Audio -> MediaResultGroup.Audio
    else -> MediaResultGroup.Other
}
