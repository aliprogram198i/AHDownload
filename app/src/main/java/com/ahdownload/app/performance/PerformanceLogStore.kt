package com.ahdownload.app.performance

import android.content.Context
import android.os.SystemClock
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.download.DownloadState
import com.ahdownload.domain.download.DownloadTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PerformanceLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val durationMs: Long,
    val operationId: String? = null,
    val taskId: String? = null,
    val platform: String = "Unknown",
    val mediaKind: String = "Unknown",
    val outcome: String,
    val candidateCount: Int? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val averageBytesPerSecond: Long = 0L,
    val peakBytesPerSecond: Long = 0L,
    val responseMs: Long? = null,
)

class PerformanceLogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<PerformanceLogEntry>>() {}.type
    private val lock = Any()
    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<PerformanceLogEntry>> = _entries.asStateFlow()

    fun recordAnalysis(
        operationId: String,
        platform: String,
        startedAtEpochMs: Long,
        durationMs: Long,
        outcome: String,
        candidateCount: Int,
    ) {
        val completedAt = System.currentTimeMillis()
        append(
            PerformanceLogEntry(
                kind = KIND_ANALYSIS,
                startedAtEpochMs = startedAtEpochMs.coerceAtLeast(0L),
                completedAtEpochMs = completedAt,
                durationMs = durationMs.coerceAtLeast(0L),
                operationId = operationId.takeIf { it.isNotBlank() },
                platform = platform.ifBlank { "Unknown" },
                outcome = outcome,
                candidateCount = candidateCount.coerceAtLeast(0),
            ),
        )
    }

    fun beginDownload(task: DownloadTask): TransferSession {
        val sourcePage = task.sourcePageUrl ?: task.sourceUrl
        val platform = runCatching { LinkAnalyzer().analyze(sourcePage)?.platform?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        return TransferSession(
            taskId = task.id,
            startedAtEpochMs = System.currentTimeMillis(),
            startedElapsedMs = SystemClock.elapsedRealtime(),
            platform = platform,
            mediaKind = task.mediaKind?.name ?: "Unknown",
        )
    }

    fun observeDownload(session: TransferSession, state: DownloadState) {
        if (state !is DownloadState.Downloading) return
        synchronized(session) {
            if (session.finished) return
            val nowElapsed = SystemClock.elapsedRealtime()
            if (session.responseMs == null) {
                session.responseMs = (nowElapsed - session.startedElapsedMs).coerceAtLeast(0L)
            }
            val currentBytes = state.bytesDownloaded.coerceAtLeast(0L)
            val previousBytes = session.lastSampleBytes
            val previousElapsed = session.lastSampleElapsedMs
            if (previousBytes != null && previousElapsed != null) {
                val elapsed = nowElapsed - previousElapsed
                val delta = currentBytes - previousBytes
                if (elapsed >= MIN_SPEED_SAMPLE_MS && delta >= 0L) {
                    val speed = PerformanceMetrics.bytesPerSecond(delta, elapsed)
                    session.peakBytesPerSecond = maxOf(session.peakBytesPerSecond, speed)
                    session.lastSampleBytes = currentBytes
                    session.lastSampleElapsedMs = nowElapsed
                }
            } else {
                session.lastSampleBytes = currentBytes
                session.lastSampleElapsedMs = nowElapsed
            }
            session.bytesDownloaded = maxOf(session.bytesDownloaded, currentBytes)
            state.totalBytes?.takeIf { it >= 0L }?.let { session.totalBytes = it }
        }
    }

    fun finishDownload(
        session: TransferSession,
        outcome: String,
        bytesDownloadedOverride: Long? = null,
        totalBytesOverride: Long? = null,
    ) {
        val entry = synchronized(session) {
            if (session.finished) return
            session.finished = true
            bytesDownloadedOverride?.takeIf { it >= 0L }?.let {
                session.bytesDownloaded = maxOf(session.bytesDownloaded, it)
            }
            totalBytesOverride?.takeIf { it >= 0L }?.let { session.totalBytes = it }
            val duration = (SystemClock.elapsedRealtime() - session.startedElapsedMs).coerceAtLeast(0L)
            PerformanceLogEntry(
                kind = KIND_DOWNLOAD,
                startedAtEpochMs = session.startedAtEpochMs,
                completedAtEpochMs = System.currentTimeMillis(),
                durationMs = duration,
                taskId = session.taskId,
                platform = session.platform,
                mediaKind = session.mediaKind,
                outcome = outcome,
                bytesDownloaded = session.bytesDownloaded,
                totalBytes = session.totalBytes,
                averageBytesPerSecond = PerformanceMetrics.bytesPerSecond(session.bytesDownloaded, duration),
                peakBytesPerSecond = session.peakBytesPerSecond,
                responseMs = session.responseMs,
            )
        }
        append(entry)
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            preferences.edit().remove(KEY_ENTRIES).apply()
        }
    }

    fun exportReport(): String {
        val snapshot = entries.value
        return buildString {
            appendLine("AHDownload Performance Log")
            appendLine("schema=1")
            appendLine("entries=${snapshot.size}")
            appendLine()
            snapshot.sortedByDescending { it.completedAtEpochMs }.forEach { item ->
                appendLine("kind=${item.kind}")
                appendLine("time=${item.completedAtEpochMs}")
                appendLine("platform=${item.platform}")
                appendLine("media_kind=${item.mediaKind}")
                appendLine("outcome=${item.outcome}")
                appendLine("duration_ms=${item.durationMs}")
                item.candidateCount?.let { appendLine("candidates=$it") }
                item.responseMs?.let { appendLine("response_ms=$it") }
                if (item.kind == KIND_DOWNLOAD) {
                    appendLine("bytes_downloaded=${item.bytesDownloaded}")
                    appendLine("total_bytes=${item.totalBytes ?: "unknown"}")
                    appendLine("average_bytes_per_second=${item.averageBytesPerSecond}")
                    appendLine("peak_bytes_per_second=${item.peakBytesPerSecond}")
                }
                appendLine()
            }
        }
    }

    private fun append(entry: PerformanceLogEntry) {
        synchronized(lock) {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val updated = (_entries.value + entry)
                .filter { it.completedAtEpochMs >= cutoff }
                .sortedByDescending { it.completedAtEpochMs }
                .take(MAX_ENTRIES)
            _entries.value = updated
            preferences.edit().putString(KEY_ENTRIES, gson.toJson(updated)).apply()
        }
    }

    private fun loadEntries(): List<PerformanceLogEntry> = runCatching {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        gson.fromJson<List<PerformanceLogEntry>>(preferences.getString(KEY_ENTRIES, null), listType)
            .orEmpty()
            .filter { it.durationMs >= 0L && it.completedAtEpochMs >= cutoff }
            .sortedByDescending { it.completedAtEpochMs }
            .take(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    class TransferSession internal constructor(
        internal val taskId: String,
        internal val startedAtEpochMs: Long,
        internal val startedElapsedMs: Long,
        internal val platform: String,
        internal val mediaKind: String,
    ) {
        internal var finished: Boolean = false
        internal var bytesDownloaded: Long = 0L
        internal var totalBytes: Long? = null
        internal var responseMs: Long? = null
        internal var lastSampleBytes: Long? = null
        internal var lastSampleElapsedMs: Long? = null
        internal var peakBytesPerSecond: Long = 0L
    }

    private companion object {
        const val PREFERENCES_NAME = "ahdownload_performance_log"
        const val KEY_ENTRIES = "entries_json"
        const val KIND_ANALYSIS = "ANALYSIS"
        const val KIND_DOWNLOAD = "DOWNLOAD"
        const val MAX_ENTRIES = 200
        const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        const val MIN_SPEED_SAMPLE_MS = 500L
    }
}

object PerformanceMetrics {
    fun bytesPerSecond(deltaBytes: Long, elapsedMs: Long): Long {
        if (deltaBytes <= 0L || elapsedMs <= 0L) return 0L
        return ((deltaBytes.toDouble() * 1000.0) / elapsedMs.toDouble())
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "—"
        val mbps = bytesPerSecond / (1024.0 * 1024.0)
        return if (mbps >= 1.0) String.format(java.util.Locale.ROOT, "%.2f MB/s", mbps)
        else String.format(java.util.Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0)
    }
}
