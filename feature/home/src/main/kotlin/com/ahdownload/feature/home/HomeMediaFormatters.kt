package com.ahdownload.feature.home

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaPresentationModel
import com.ahdownload.domain.resolver.MediaResultRecommendation

/**
 * Presentation-only formatting for the unified media result card.
 * Kept separate from the main Compose screen so labels and format decisions can be
 * reviewed and tested without making navigation or composable state more complex.
 */
internal fun audioSectionSubtitle(
    audioOptions: List<MediaPresentationModel>,
    extractionSource: MediaPresentationModel?,
): String {
    val hasDirectAudio = audioOptions.any { it.candidate.format.kind == MediaKind.Audio }
    return when {
        hasDirectAudio ->
            "الصيغ المعروضة هي مسارات الصوت المتاحة فعليًا من المصدر"
        extractionSource != null ->
            "استخراج مسار الصوت الموجود داخل الفيديو؛ لا نعرض صيغة تحويل غير مدعومة"
        else ->
            "الصيغ الصوتية المتاحة فعليًا لهذا الرابط"
    }
}

internal fun formatOptionPrimaryLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
): String {
    if (!audioOnly) {
        return model.candidate.format.height
            ?.takeIf { it > 0 }
            ?.let { "${it}p" }
            ?: model.qualityLabel.takeIf { it.isNotBlank() && !it.equals("Video", ignoreCase = true) }
            ?: "جودة غير معروفة"
    }

    return when (model.candidate.format.kind) {
        MediaKind.Audio -> model.candidate.format.bitrateKbps
            ?.takeIf { it > 0 }
            ?.let { "$it kbps" }
            ?: model.qualityLabel.takeIf { it.isNotBlank() && !it.equals("Audio", ignoreCase = true) }
            ?: containerLabel(model.candidate.format.container)
        MediaKind.Video -> "استخراج"
        else -> "صوت"
    }
}

internal fun formatOptionSecondaryLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
): String {
    val format = model.candidate.format

    if (audioOnly) {
        return when (format.kind) {
            MediaKind.Audio -> buildList {
                normalizeCodecForUi(format.audioCodec)?.let(::add)
                containerLabel(format.container).takeIf { it != "صيغة غير معروفة" }?.let(::add)
            }.distinct().joinToString(" · ").ifBlank { "مسار صوتي مباشر" }

            MediaKind.Video -> buildList {
                add("من " + (format.height?.let { "${it}p" } ?: "الفيديو"))
                normalizeCodecForUi(format.audioCodec)?.let(::add)
            }.joinToString(" · ")

            else -> "مصدر صوتي"
        }
    }

    return buildList {
        add(containerLabel(format.container))
        normalizeCodecForUi(format.videoCodec)?.let(::add)
        normalizeCodecForUi(format.audioCodec)?.let(::add)
    }.distinct().joinToString(" · ")
}

internal fun formatOptionMetaLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
    mergeAudioWhenMissing: Boolean = false,
): String {
    val format = model.candidate.format
    return if (audioOnly) {
        buildList {
            model.sizeLabel?.let(::add)
            model.fpsLabel?.let(::add)
        }.joinToString(" · ").ifBlank {
            when (format.kind) {
                MediaKind.Audio -> "جودة الصوت المتاحة"
                MediaKind.Video -> "استخراج الصوت من المصدر"
                else -> "صوت"
            }
        }
    } else {
        buildList {
            model.fpsLabel?.let(::add)
            model.sizeLabel?.let(::add)
            if (format.kind == MediaKind.Video) {
                add(
                    when {
                        format.hasAudio -> "صوت مدمج"
                        model.candidate.id == "direct" -> "ملف مباشر؛ وجود الصوت غير مؤكد"
                        mergeAudioWhenMissing -> "سيُدمج مسار الصوت المتاح عند التنزيل"
                        else -> "فيديو فقط · لا يوجد مسار صوت مؤكد"
                    },
                )
            }
        }.joinToString(" · ").ifBlank {
            when (format.kind) {
                MediaKind.Video -> if (format.hasAudio || mergeAudioWhenMissing) {
                    "صوت مدمج أو مسار صوت متاح"
                } else {
                    "فيديو فقط · لا يوجد مسار صوت مؤكد"
                }
                MediaKind.Audio -> "مسار صوت مباشر"
                else -> "نوع الوسائط غير محدد"
            }
        }
    }
}

internal fun formatOptionAccessibilityLabel(
    model: MediaPresentationModel,
    audioOnly: Boolean,
    mergeAudioWhenMissing: Boolean = false,
): String = buildList {
    add(formatOptionPrimaryLabel(model, audioOnly))
    formatOptionSecondaryLabel(model, audioOnly)
        .takeIf { it.isNotBlank() }
        ?.let(::add)
    formatOptionMetaLabel(model, audioOnly, mergeAudioWhenMissing)
        .takeIf { it.isNotBlank() }
        ?.let(::add)
}.joinToString(" · ")

internal fun audioFormatLabel(model: MediaPresentationModel): String {
    val container = containerLabel(model.candidate.format.container)
    return if (container == "صيغة غير معروفة") {
        model.qualityLabel
    } else {
        container
    }
}

internal fun buildQualityLine(model: MediaPresentationModel): String {
    val format = model.candidate.format
    return when (format.kind) {
        MediaKind.Video -> (format.height?.takeIf { it > 0 }?.let { "${it}p" } ?: "جودة غير معروفة") + " · " + containerLabel(format.container)
        MediaKind.Audio -> model.qualityLabel + " · " + containerLabel(format.container)
        else -> model.qualityLabel
    }
}

internal fun normalizeCodecForUi(codec: String?): String? {
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

internal fun logSelection(
    logger: DiagnosticLogger,
    model: MediaPresentationModel,
    selectedId: String?,
) {
    logger.log(
        DiagnosticLevel.INFO,
        "SMART_CENTER_OPTION_SELECTED",
        "اختار المستخدم مصدرًا من النتائج",
        "ui.smart_center.selection",
        mapOf(
            "candidate_id" to model.candidate.id,
            "quality" to model.qualityLabel,
            "group" to model.group.name,
            "recommendation" to model.recommendation.name,
            "score" to model.score.toString(),
            "previous_selection" to (selectedId ?: "none"),
        ),
        null,
    )
}

internal fun containerLabel(container: com.ahdownload.domain.resolver.MediaContainer): String =
    when (container) {
        com.ahdownload.domain.resolver.MediaContainer.Mp4 -> "MP4"
        com.ahdownload.domain.resolver.MediaContainer.Webm -> "WebM"
        com.ahdownload.domain.resolver.MediaContainer.Mkv -> "MKV"
        com.ahdownload.domain.resolver.MediaContainer.Mov -> "MOV"
        com.ahdownload.domain.resolver.MediaContainer.M4a -> "M4A"
        com.ahdownload.domain.resolver.MediaContainer.Mp3 -> "MP3"
        com.ahdownload.domain.resolver.MediaContainer.Aac -> "AAC"
        com.ahdownload.domain.resolver.MediaContainer.Ogg -> "OGG"
        com.ahdownload.domain.resolver.MediaContainer.Flac -> "FLAC"
        com.ahdownload.domain.resolver.MediaContainer.Wav -> "WAV"
        com.ahdownload.domain.resolver.MediaContainer.ThreeGp -> "3GP"
        com.ahdownload.domain.resolver.MediaContainer.Avi -> "AVI"
        com.ahdownload.domain.resolver.MediaContainer.Unknown -> "صيغة غير معروفة"
    }

internal fun platformLabel(platform: String): String = when (platform) {
    "YouTube" -> "YouTube"
    "Instagram" -> "Instagram"
    "Facebook" -> "Facebook"
    "TikTok" -> "TikTok"
    "X" -> "X"
    "Pinterest" -> "Pinterest"
    "Reddit" -> "Reddit"
    "Twitch" -> "Twitch"
    "Vimeo" -> "Vimeo"
    "DirectMedia" -> "رابط مباشر"
    "Web" -> "موقع ويب"
    else -> platform
}

internal fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Video -> "فيديو"
    MediaKind.Audio -> "صوت"
    MediaKind.Image -> "صورة"
    MediaKind.Unknown -> "محتوى"
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return (totalSeconds / 60L).toString() + ":" + (totalSeconds % 60L).toString().padStart(2, '0')
}

/** Avoid reporting a result card for an error-only UI state. */
internal fun shouldEmitSmartCenterResultPresented(hasResolution: Boolean): Boolean = hasResolution
