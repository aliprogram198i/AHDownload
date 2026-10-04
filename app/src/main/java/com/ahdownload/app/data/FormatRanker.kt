package com.ahdownload.app.data

/**
 * Deterministic format ranking used by the UI and download admission layer.
 * The goal is to present one sensible default without hiding advanced choices.
 */
object FormatRanker {
    fun rankVideo(formats: List<ResolvedFormat>): List<ResolvedFormat> =
        formats.sortedWith(compareByDescending<ResolvedFormat> { videoScore(it) }
            .thenByDescending { it.height ?: 0 }
            .thenBy { it.sizeBytes ?: Long.MAX_VALUE })

    fun rankAudio(formats: List<ResolvedFormat>): List<ResolvedFormat> =
        formats.sortedWith(compareByDescending<ResolvedFormat> { audioScore(it) }
            .thenByDescending { it.abr ?: 0.0 }
            .thenBy { it.sizeBytes ?: Long.MAX_VALUE })

    fun recommendedVideo(formats: List<ResolvedFormat>): ResolvedFormat? =
        rankVideo(formats).firstOrNull()

    fun recommendedAudio(formats: List<ResolvedFormat>): ResolvedFormat? =
        rankAudio(formats).firstOrNull()

    private fun videoScore(format: ResolvedFormat): Double {
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
        format.sizeBytes?.let { size ->
            if (size > 0L) {
                val gb = size.toDouble() / (1024.0 * 1024.0 * 1024.0)
                score -= (gb.coerceAtMost(4.0) * 2.5)
            }
        }
        return score
    }

    private fun audioScore(format: ResolvedFormat): Double {
        val abr = format.abr ?: 0.0
        var score = when {
            abr >= 256.0 -> 100.0 - ((abr - 320.0).coerceAtLeast(0.0) / 10.0)
            abr >= 192.0 -> 92.0 + (abr - 192.0) / 16.0
            abr >= 128.0 -> 72.0 + (abr - 128.0) / 8.0
            abr > 0.0 -> 55.0
            else -> 45.0
        }
        score += containerScore(format.ext)
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
