package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.canBeRemovedFromHistory
import kotlinx.coroutines.CancellationException

/**
 * Removes a task from history without deleting its output file.
 * Returns true only after the repository operation completes successfully.
 */
internal suspend fun deleteDownloadHistoryRecord(
    repository: DownloadRepository,
    record: DownloadRecord,
): Boolean {
    if (!record.status.canBeRemovedFromHistory) return false

    return try {
        repository.delete(record.task.id)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
