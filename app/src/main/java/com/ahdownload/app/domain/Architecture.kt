package com.ahdownload.app.domain

enum class MediaType { VIDEO, AUDIO, FILE, UNKNOWN }
enum class DownloadStatus { CREATED, VALIDATING, RESOLVING, READY, QUEUED, DOWNLOADING, PAUSED, RETRYING, CANCELLED, FAILED, PROCESSING, FINALIZING, COMPLETED }

data class MediaFormat(
    val id: String, val type: MediaType, val container: String?, val codec: String?,
    val resolution: String?, val fps: Int?, val bitrate: Long?, val hasAudio: Boolean,
    val hasVideo: Boolean, val estimatedSize: Long?, val url: String
)

data class MediaInfo(
    val source: String, val type: MediaType, val title: String, val thumbnailUrl: String?,
    val durationMs: Long?, val sizeBytes: Long?, val formats: List<MediaFormat>
)

data class DownloadJob(
    val id: String, val sourceUrl: String, val title: String, val formatUrl: String,
    val status: DownloadStatus, val progress: Int, val downloadedBytes: Long, val totalBytes: Long?
)

interface SourceResolver {
    suspend fun canResolve(url: String): Boolean
    suspend fun resolve(url: String): Result<MediaInfo>
}

interface MediaProcessor {
    suspend fun extractAudio(input: String, output: String): Result<Unit>
    suspend fun trim(input: String, output: String, startMs: Long, endMs: Long): Result<Unit>
    suspend fun split(input: String, outputs: List<String>): Result<Unit>
    suspend fun thumbnail(input: String, output: String): Result<Unit>
}
