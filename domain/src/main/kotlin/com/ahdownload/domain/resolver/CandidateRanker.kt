package com.ahdownload.domain.resolver

class CandidateRanker {
    fun rank(
        candidates: List<MediaCandidate>,
        requestedKind: com.ahdownload.domain.model.MediaKind? = null,
    ): List<MediaCandidate> {
        return candidates
            .asSequence()
            .filter { requestedKind == null || it.format.kind == requestedKind }
            .sortedWith(
                compareByDescending<MediaCandidate> { it.format.hasVideo }
                    .thenByDescending { it.format.hasAudio }
                    .thenByDescending { it.format.height ?: 0 }
                    .thenByDescending { it.format.bitrateKbps ?: 0 }
                    .thenByDescending { it.format.fileSizeBytes ?: 0L },
            )
            .toList()
    }
}
