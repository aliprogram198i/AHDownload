package com.ahdownload.app.data

import com.ahdownload.app.domain.DownloadProfile

/**
 * Deterministic format ranking used by the UI and download admission layer.
 * The goal is to present one sensible default without hiding advanced choices.
 */
object FormatRanker {
    fun rankVideo(formats: List<ResolvedFormat>, profile: DownloadProfile = DownloadProfile.BALANCED): List<ResolvedFormat> =
        formats.sortedWith(compareByDescending<ResolvedFormat> { videoScore(it, profile) }
            .thenByDescending { it.height ?: 0 }
            .thenBy { it.sizeBytes ?: Long.MAX_VALUE })

    fun rankAudio(formats: List<ResolvedFormat>, profile: DownloadProfile = DownloadProfile.BALANCED): List<ResolvedFormat> =
        formats.sortedWith(compareByDescending<ResolvedFormat> { audioScore(it, profile) }
            .thenByDescending { it.abr ?: 0.0 }
            .thenBy { it.sizeBytes ?: Long.MAX_VALUE })

    fun recommendedVideo(formats: List<ResolvedFormat>, profile: DownloadProfile = DownloadProfile.BALANCED): ResolvedFormat? =
        rankVideo(formats, profile).firstOrNull()

    fun recommendedAudio(formats: List<ResolvedFormat>, profile: DownloadProfile = DownloadProfile.BALANCED): ResolvedFormat? =
        rankAudio(formats, profile).firstOrNull()

    private fun videoScore(format: ResolvedFormat, profile: DownloadProfile): Double {
        val height = format.height ?: 0
        var score = when {
            height in 720..1080 -> 100.0 + (1080 - height) / 100.0
            height in 480..719 -> 78.0 + (height - 480) / 100.0
            height in 1081..1440 -> 92.0 - (height - 1080) / 40.0
            height > 1440 -> 72.0 - (height - 1440) / 120.0
            height > 0 -> 55.0
            else -> 45.0
        }
        if (format.hasVideo && format.hasAudio) score += 22.0
        if (format.mergeRequired) score -= 4.0
        score += containerScore(format.ext)
        when (profile) {
            DownloadProfile.BALANCED -> Unit
            DownloadProfile.COMPATIBILITY -> {
                if (format.codec.orEmpty().lowercase().contains("avc")) score += 14.0
                if (format.ext.equals("mp4", true)) score += 8.0
                if (format.fps != null && format.fps <= 60.0) score += 2.0
            }
            DownloadProfile.HIGHEST_QUALITY -> {
                score += (format.height ?: 0) / 120.0
                score += (format.tbr ?: 0.0) / 100.0
            }
        }
        format.sizeBytes?.let { size ->
            if (size > 0L) {
                val gb = size.toDouble() / (1024.0 * 1024.0 * 1024.0)
                score -= (gb.coerceAtMost(4.0) * 2.5)
            }
        }
        return score
    }

    private fun audioScore(format: ResolvedFormat, profile: DownloadProfile): Double {
        val abr = format.abr ?: 0.0
        var score = when {
            abr >= 256.0 -> 100.0 - ((abr - 320.0).coerceAtLeast(0.0) / 10.0)
            abr >= 192.0 -> 92.0 + (abr - 192.0) / 16.0
            abr >= 128.0 -> 72.0 + (abr - 128.0) / 8.0
            abr > 0.0 -> 55.0
            else -> 45.0
        }
        score += containerScore(format.ext)
        if (profile == DownloadProfile.HIGHEST_QUALITY) score += abr / 64.0
        if (profile == DownloadProfile.COMPATIBILITY && format.ext.lowercase() in setOf("mp3", "m4a")) score += 6.0
        format.sizeBytes?.let { size ->
            if (size > 0L) score -= (size.toDouble() / (1024.0 * 1024.0 * 1024.0)).coerceAtMost(2.0)
        }
        return score
    }

    private fun containerScore(ext: String): Double = when (ext.lowercase()) {
        "mp4", "m4a", "mp3" -> 3.0
        "webm" -> 1.0
        else -> 0.0
    }
}
