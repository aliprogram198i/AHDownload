package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import kotlin.math.roundToInt

class SmartResultEngine {
    fun build(candidates: List<MediaCandidate>, maxVideo: Int = 8, maxAudio: Int = 8): SmartResultSet {
        val parsed = candidates
            .filter { it.sourceUrl.startsWith("http://") || it.sourceUrl.startsWith("https://") }
            .map(::normalize)

        // Present one best source per actual quality tier. Multiple YouTube
        // clients/containers can expose the same resolution or bitrate; those
        // are alternate sources, not separate quality buttons.
        val video = parsed
            .filter { it.group == MediaResultGroup.Video }
            .sortedWith(videoComparator)
            .distinctBy { presentationQualityKey(it) }
        val audio = parsed
            .filter { it.group == MediaResultGroup.Audio }
            .sortedWith(audioComparator)
            .distinctBy { presentationQualityKey(it) }
        val other = parsed
            .filter { it.group == MediaResultGroup.Other }
            .distinctBy { it.candidate.sourceUrl }
        val normalized = (video + audio + other).sortedByDescending { it.score }
        val playableVideo = video.filter { it.candidate.format.hasVideo && it.candidate.format.hasAudio }
        val presentationVideo = playableVideo.ifEmpty { video }

        val bestOverall = presentationVideo.firstOrNull()
            ?: audio.firstOrNull()
            ?: normalized.firstOrNull()
        val bestQuality = presentationVideo.maxWithOrNull(
            compareBy<MediaPresentationModel> { it.candidate.format.height ?: 0 }
                .thenBy { it.candidate.format.fps ?: 0.0 }
                .thenBy { it.candidate.format.bitrateKbps ?: 0 }
        ) ?: audio.maxWithOrNull(compareBy { it.candidate.format.bitrateKbps ?: 0 })
        val smallestSize = normalized
            .filter { (it.candidate.format.fileSizeBytes ?: 0L) > 0L }
            .minByOrNull { it.candidate.format.fileSizeBytes ?: Long.MAX_VALUE }

        val recommended = normalized.map { item ->
            when {
                item.candidate.id == bestOverall?.candidate?.id -> item.copy(recommendation = MediaResultRecommendation.BestOverall)
                item.candidate.id == bestQuality?.candidate?.id -> item.copy(recommendation = MediaResultRecommendation.BestQuality)
                item.candidate.id == smallestSize?.candidate?.id -> item.copy(recommendation = MediaResultRecommendation.SmallestSize)
                else -> item
            }
        }

        val finalBestOverall = recommended.firstOrNull { it.recommendation == MediaResultRecommendation.BestOverall }
        val finalBestQuality = recommended.firstOrNull { it.recommendation == MediaResultRecommendation.BestQuality }
        val finalSmallest = recommended.firstOrNull { it.recommendation == MediaResultRecommendation.SmallestSize }
        val visibleIds = buildList {
            finalBestOverall?.candidate?.id?.let(::add)
            addAll(presentationVideo.take(maxVideo).map { it.candidate.id })
            addAll(audio.take(maxAudio).map { it.candidate.id })
        }.toSet()

        return SmartResultSet(
            all = recommended,
            visible = recommended.filter { it.candidate.id in visibleIds },
            video = recommended.filter { it.group == MediaResultGroup.Video },
            audio = recommended.filter { it.group == MediaResultGroup.Audio },
            other = other,
            bestOverall = finalBestOverall,
            bestQuality = finalBestQuality,
            smallestSize = finalSmallest,
        )
    }

    private fun normalize(candidate: MediaCandidate): MediaPresentationModel {
        val f = candidate.format
        val height = f.height ?: 0
        val bitrate = f.bitrateKbps ?: 0
        val fps = f.fps ?: 0.0
        val muxedBonus = if (f.hasVideo && f.hasAudio) 350 else 0
        val containerBonus = if (f.container == MediaContainer.Mp4 || f.container == MediaContainer.M4a) 120 else 0
        val codecBonus = when {
            f.videoCodec?.startsWith("avc", true) == true -> 100
            f.videoCodec?.startsWith("vp9", true) == true -> 80
            f.videoCodec?.startsWith("av01", true) == true -> 60
            else -> 0
        }
        val score = (height * 2.2 + bitrate * 0.12 + fps * 3).roundToInt() + muxedBonus + containerBonus + codecBonus
        val group = candidate.resultGroup()
        val dedupeKey = listOf(
            group.name,
            height,
            f.width ?: 0,
            f.container.name,
            normalizeCodec(f.videoCodec),
            normalizeCodec(f.audioCodec),
            f.fps?.roundToInt() ?: 0,
            (bitrate / 16) * 16
        ).joinToString("|")
        return MediaPresentationModel(
            candidate = candidate,
            group = group,
            score = score,
            sourceState = if (candidate.sourceUrl.isNotBlank()) MediaSourceState.Ready else MediaSourceState.Unavailable,
            qualityLabel = qualityLabel(candidate),
            codecLabel = codecLabel(candidate),
            fpsLabel = f.fps?.let { "${it.roundToInt()} FPS" },
            sizeLabel = f.fileSizeBytes?.takeIf { it > 0 }?.let(::formatBytes),
            dedupeKey = dedupeKey,
        )
    }

    private fun presentationQualityKey(item: MediaPresentationModel): String {
        val format = item.candidate.format
        return when (item.group) {
            MediaResultGroup.Video -> format.height
                ?.takeIf { it > 0 }
                ?.let { "video-height:$it" }
                // If the resolver cannot provide height metadata, these are not
                // distinct user-visible quality options. Keep one best fallback
                // instead of rendering several identical "Video" placeholder cards.
                ?: "video-unknown"
            MediaResultGroup.Audio -> format.bitrateKbps
                ?.takeIf { it > 0 }
                ?.let { "audio-bitrate:${it / 16 * 16}" }
                ?: "audio-unknown"
            MediaResultGroup.Other -> item.candidate.sourceUrl
        }
    }

    private fun qualityLabel(c: MediaCandidate): String = when {
        c.format.kind == MediaKind.Video && c.format.height != null -> "${c.format.height}p"
        c.format.kind == MediaKind.Audio && c.format.bitrateKbps != null -> "${c.format.bitrateKbps} kbps"
        c.format.kind == MediaKind.Video -> "Video"
        c.format.kind == MediaKind.Audio -> "Audio"
        else -> "Media"
    }

    private fun codecLabel(c: MediaCandidate): String? {
        val v = normalizeCodec(c.format.videoCodec)
        val a = normalizeCodec(c.format.audioCodec)
        return listOfNotNull(v, a).joinToString(" · ").ifBlank { null }
    }

    private fun normalizeCodec(codec: String?): String? {
        val value = codec?.substringBefore(',')?.trim()?.lowercase() ?: return null
        return when {
            value.startsWith("avc") -> "H.264"
            value.startsWith("av01") -> "AV1"
            value.startsWith("vp9") -> "VP9"
            value.startsWith("vp8") -> "VP8"
            value.startsWith("mp4a") -> "AAC"
            value.startsWith("opus") -> "Opus"
            value.startsWith("vorbis") -> "Vorbis"
            else -> codec.substringBefore(',').trim().takeIf { it.isNotBlank() }
        }
    }

    private val videoComparator = compareByDescending<MediaPresentationModel> { it.candidate.format.hasAudio }
        .thenByDescending { it.candidate.format.height ?: 0 }
        .thenByDescending { it.candidate.format.fps ?: 0.0 }
        .thenByDescending { videoCodecCompatibility(it.candidate.format.videoCodec) }
        .thenByDescending { audioCodecCompatibility(it.candidate.format.audioCodec) }
        .thenByDescending { videoContainerCompatibility(it.candidate.format.container) }
        .thenByDescending { it.candidate.format.bitrateKbps ?: 0 }
        .thenBy { it.candidate.format.fileSizeBytes ?: Long.MAX_VALUE }

    private val audioComparator = compareByDescending<MediaPresentationModel> { it.candidate.format.bitrateKbps ?: 0 }
        .thenByDescending { audioContainerCompatibility(it.candidate.format.container) }
        .thenByDescending { audioCodecCompatibility(it.candidate.format.audioCodec) }
        .thenBy { it.candidate.format.fileSizeBytes ?: Long.MAX_VALUE }

    private fun videoCodecCompatibility(codec: String?): Int = when (normalizeCodec(codec)) {
        "H.264" -> 3
        "VP9" -> 2
        "AV1" -> 1
        else -> 0
    }

    private fun audioCodecCompatibility(codec: String?): Int = when (normalizeCodec(codec)) {
        "AAC" -> 3
        "Opus" -> 2
        "Vorbis" -> 1
        else -> 0
    }

    private fun videoContainerCompatibility(container: MediaContainer): Int = when (container) {
        MediaContainer.Mp4 -> 3
        MediaContainer.Webm -> 2
        MediaContainer.Mkv -> 1
        else -> 0
    }

    private fun audioContainerCompatibility(container: MediaContainer): Int = when (container) {
        MediaContainer.M4a -> 3
        MediaContainer.Mp3 -> 2
        MediaContainer.Aac -> 2
        MediaContainer.Ogg -> 1
        MediaContainer.Flac -> 1
        MediaContainer.Wav -> 1
        else -> 0
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GB"
    }
}
