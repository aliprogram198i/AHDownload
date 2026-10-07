package com.ahdownload.domain.download

data class DownloadRecord(
    val task: DownloadTask,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val failureCode: String?,
    val failureDetail: String?,
    /** Final user-visible destination URI after publication/copy, when available. */
    val destinationUri: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(task.id.isNotBlank()) { "task.id must not be blank" }
        require(task.sourceUrl.isNotBlank()) { "task.sourceUrl must not be blank" }
        require(task.destinationPath.isNotBlank()) { "task.destinationPath must not be blank" }
        require(bytesDownloaded >= 0) { "bytesDownloaded must be >= 0" }
        require(totalBytes == null || totalBytes >= 0) { "totalBytes must be >= 0" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must be >= 0" }
        require(updatedAtEpochMs >= createdAtEpochMs) {
            "updatedAtEpochMs must be >= createdAtEpochMs"
        }
    }
}
