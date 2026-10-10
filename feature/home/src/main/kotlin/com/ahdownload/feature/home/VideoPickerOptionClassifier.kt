package com.ahdownload.feature.home

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaPresentationModel

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
