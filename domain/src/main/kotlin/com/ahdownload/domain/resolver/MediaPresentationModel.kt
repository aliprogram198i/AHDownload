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
}

internal fun MediaCandidate.resultGroup(): MediaResultGroup = when (format.kind) {
    MediaKind.Video -> MediaResultGroup.Video
    MediaKind.Audio -> MediaResultGroup.Audio
    else -> MediaResultGroup.Other
}
