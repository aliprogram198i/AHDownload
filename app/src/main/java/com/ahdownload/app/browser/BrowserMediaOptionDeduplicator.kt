package com.ahdownload.app.browser

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import java.util.Locale

/**
 * Builds stable, user-visible browser options from resolver candidates.
 *
 * Different CDN URLs can describe the same option. The browser displays one option per
 * visible quality/format while retaining the first candidate and its request context.
 */
internal object BrowserMediaOptionDeduplicator {
    fun videoOptions(candidates: List<MediaCandidate>): List<MediaCandidate> =
        candidates.asSequence()
            .filter { it.format.kind == MediaKind.Video && it.format.hasVideo }
            .distinctBy { candidate ->
                val format = candidate.format
                VideoOptionKey(
                    container = format.container,
                    streamingType = streamingType(candidate),
                    width = format.width,
                    height = format.height,
                    fps = format.fps,
                    bitrateKbps = format.bitrateKbps,
                    videoCodec = normalizeCodec(format.videoCodec),
                    audioCodec = normalizeCodec(format.audioCodec),
                    hasAudio = format.hasAudio,
                    hasCompanionAudio = !candidate.companionAudioSourceUrl.isNullOrBlank(),
                )
            }
            .toList()

    fun audioSources(candidates: List<MediaCandidate>): List<MediaCandidate> =
        candidates.asSequence()
            // The extraction section must contain audio-only sources. A muxed MP4 or
            // HLS video representation may carry audio, but it is not an audio-only source.
            .filter { !it.format.hasVideo && (it.format.hasAudio || it.format.kind == MediaKind.Audio) }
            .distinctBy(::audioSourceLabel)
            .toList()

    fun videoOptionTitle(candidate: MediaCandidate): String {
        val format = candidate.format
        return buildList {
            format.height?.let { add("${it}p") }
            if (format.width != null && format.height != null) {
                add("${format.width}×${format.height}")
            }
            add(sourceFormatLabel(candidate))
            format.videoCodec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
            format.audioCodec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
            format.fps?.takeIf { it > 0 }?.let { add("${it} fps") }
            format.bitrateKbps?.takeIf { it > 0 }?.let { add("${it} kbps") }
        }.distinct().joinToString(" • ")
    }

    /**
     * Audio chips expose the available quality metadata so an audio-only source does not
     * collapse to an ambiguous container-only label when bitrate or codec is known.
     */
    fun audioSourceLabel(candidate: MediaCandidate): String {
        val format = candidate.format
        return buildList {
            if (format.hasVideo) {
                format.height?.let { add("${it}p") }
                if (format.width != null && format.height != null) {
                    add("${format.width}×${format.height}")
                }
                format.videoCodec?.takeIf { it.isNotBlank() }?.let {
                    add("Video ${it.uppercase(Locale.ROOT)}")
                }
            }
            format.audioCodec
                ?.takeIf { it.isNotBlank() && !it.equals(format.container.name, ignoreCase = true) }
                ?.let { add(it.uppercase(Locale.ROOT)) }
            format.bitrateKbps?.takeIf { it > 0 }?.let { add("${it} kbps") }
            add(sourceFormatLabel(candidate))
            add(
                when {
                    format.hasVideo && format.hasAudio -> "صوت مضمّن"
                    format.hasVideo -> "صوت غير مؤكد"
                    else -> "صوت فقط"
                },
            )
        }.distinct().joinToString(" • ")
    }

    private fun sourceFormatLabel(candidate: MediaCandidate): String =
        streamingType(candidate) ?: candidate.format.container.name

    private fun streamingType(candidate: MediaCandidate): String? {
        if (!candidate.streamingManifest) return null
        val extension = runCatching {
            java.net.URI(candidate.sourceUrl).path.orEmpty()
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
        }.getOrDefault("")
        return when (extension) {
            "m3u8" -> "HLS"
            "mpd" -> "DASH"
            else -> "بث"
        }
    }

    private fun normalizeCodec(codec: String?): String? =
        codec?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private data class VideoOptionKey(
        val container: MediaContainer,
        val streamingType: String?,
        val width: Int?,
        val height: Int?,
        val fps: Double?,
        val bitrateKbps: Int?,
        val videoCodec: String?,
        val audioCodec: String?,
        val hasAudio: Boolean,
        val fileSizeBytes: Long?,
        val hasCompanionAudio: Boolean,
    )
}
