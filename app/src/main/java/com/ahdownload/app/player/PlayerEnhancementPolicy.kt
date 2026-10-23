package com.ahdownload.app.player

import com.ahdownload.domain.download.DownloadRecord

internal enum class PlayerAspectMode(val label: String) {
    FIT("ملاءمة"),
    FILL("تمديد"),
    ZOOM("قص"),
    WIDE_16_9("16:9"),
    CLASSIC_4_3("4:3"),
}

internal object PlayerEnhancementPolicy {
    const val SEEK_INTERVAL_MS = 10_000L
    val supportedSpeeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun seekTarget(currentMs: Long, durationMs: Long, forward: Boolean): Long {
        if (durationMs <= 0L) return 0L
        val current = currentMs.coerceIn(0L, durationMs)
        return if (forward) {
            if (durationMs - current < SEEK_INTERVAL_MS) durationMs else current + SEEK_INTERVAL_MS
        } else {
            (current - SEEK_INTERVAL_MS).coerceAtLeast(0L)
        }
    }

    fun aspectRatio(mode: PlayerAspectMode, sourceRatio: Float): Float = when (mode) {
        PlayerAspectMode.WIDE_16_9 -> 16f / 9f
        PlayerAspectMode.CLASSIC_4_3 -> 4f / 3f
        else -> sourceRatio.takeIf { it.isFinite() && it in 0.25f..4f } ?: (16f / 9f)
    }

    fun <T> moveItem(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return items
        return items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun restoreQueueOrder(records: List<DownloadRecord>, savedTaskIds: List<String>): List<DownloadRecord> {
        if (records.size < 2 || savedTaskIds.isEmpty()) return records
        val byId = records.associateBy { it.task.id }
        val seen = HashSet<String>()
        return buildList {
            savedTaskIds.forEach { id -> byId[id]?.let { if (seen.add(id)) add(it) } }
            records.forEach { if (seen.add(it.task.id)) add(it) }
        }
    }
}
