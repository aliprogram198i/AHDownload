package com.ahdownload.feature.home

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.validation.CandidateValidationResult

/**
 * Keeps complete/mergeable video choices separate from safe video-only fallbacks.
 * A browser-observed video-only URL is still downloadable as a video, but must not
 * be presented as video+audio unless a companion audio source is known.
 */
internal data class VideoPickerOptionGroups(
    val mainOptions: List<MediaPresentationModel>,
    val videoOnlyFallback: List<MediaPresentationModel>,
) {
    val selectableOptions: List<MediaPresentationModel>
        get() = (mainOptions + videoOnlyFallback).distinctBy { it.candidate.id }
}

internal fun classifyVideoPickerOptions(
    primaryOptions: List<MediaPresentationModel>,
    directAudioAvailable: Boolean,
): VideoPickerOptionGroups {
    val videoSources = primaryOptions.filter {
        it.candidate.format.kind == MediaKind.Video && it.candidate.format.hasVideo
    }
    return VideoPickerOptionGroups(
        mainOptions = videoSources.filter {
            it.candidate.format.hasAudio || directAudioAvailable || it.candidate.id == "direct"
        },
        videoOnlyFallback = videoSources.filter {
            !it.candidate.format.hasAudio && !directAudioAvailable && it.candidate.id != "direct"
        },
    )
}

/**
 * Orders audio-only sources for video muxing. Prefer widely supported AAC/MP4
 * audio first, then fall back through the remaining independent audio sources.
 * Source URLs and request headers are kept only in memory.
 */
internal fun rankedCompanionAudioCandidates(
    candidates: List<MediaCandidate>,
): List<MediaCandidate> = candidates
    .asSequence()
    .filter { candidate ->
        candidate.format.kind == MediaKind.Audio &&
            candidate.format.hasAudio &&
            (candidate.sourceUrl.startsWith("https://", ignoreCase = true) ||
                candidate.sourceUrl.startsWith("http://", ignoreCase = true))
    }
    .distinctBy { it.id to it.sourceUrl }
    .sortedWith(
        compareByDescending<MediaCandidate> { candidate ->
            val format = candidate.format
            format.container == MediaContainer.M4a ||
                format.container == MediaContainer.Aac ||
                format.audioCodec?.startsWith("mp4a", ignoreCase = true) == true
        }
            .thenByDescending { it.format.bitrateKbps ?: 0 }
            .thenBy { it.id },
    )
    .toList()

internal data class ValidatedCompanionAudioSource(
    val candidate: MediaCandidate,
    val validation: CandidateValidationResult.Valid,
)

/**
 * Tries each eligible audio source in deterministic preference order. A rejected
 * audio URL must not prevent trying the other formats from the same resolution.
 */
internal suspend fun validateCompanionAudioCandidates(
    candidates: List<MediaCandidate>,
    validate: suspend (MediaCandidate) -> CandidateValidationResult,
): ValidatedCompanionAudioSource? {
    for (candidate in rankedCompanionAudioCandidates(candidates)) {
        val result = validate(candidate)
        if (result is CandidateValidationResult.Valid) {
            return ValidatedCompanionAudioSource(candidate, result)
        }
    }
    return null
}
