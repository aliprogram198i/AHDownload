package com.ahdownload.app.download

import android.content.Context
import androidx.core.content.FileProvider
import com.ahdownload.app.AHDownloadApplication
import com.ahdownload.app.diagnostics.DownloadScopedDiagnosticLogger
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTask
import com.ahdownload.app.settings.DownloadLocationStore
import com.ahdownload.app.settings.SelectedDirectoryStorage

internal sealed interface DownloadDestinationPublishOutcome {
    data class Published(val record: DownloadRecord) : DownloadDestinationPublishOutcome
    data class Failed(val detail: String) : DownloadDestinationPublishOutcome
}

/**
 * Publishes a completed local staging file to the user's selected destination.
 * Transfer and download-task state remain coordinated by DownloadWorker.
 */
internal class DownloadDestinationPublisher(
    private val applicationContext: Context,
) {
    suspend fun publish(
        record: DownloadRecord,
        task: DownloadTask,
        diagnostics: DownloadScopedDiagnosticLogger,
    ): DownloadDestinationPublishOutcome {
        var completedRecord = record
                val locationStore = DownloadLocationStore(applicationContext)
                val treeUri = locationStore.persistedUri()
                if (treeUri != null) {
                    val localFile = java.io.File(task.destinationPath)
                    val copied = runCatching {
                        SelectedDirectoryStorage.copyFromLocal(
                            context = applicationContext,
                            source = localFile,
                            treeUri = treeUri,
                            displayName = localFile.name,
                        )
                    }
                    if (copied.isSuccess) {
                        val destinationUri = copied.getOrThrow()
                        localFile.delete()
                        completedRecord = completedRecord.copy(
                            destinationUri = destinationUri.toString(),
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                        diagnostics.log(
                            DiagnosticLevel.INFO,
                            "DOWNLOAD_DESTINATION_COMMITTED",
                            "تم نقل الوسيط المكتمل إلى المسار الذي اختاره المستخدم",
                            "download.destination",
                            mapOf(
                                "task_id" to task.id,
                                "destination_mode" to "CUSTOM_DIRECTORY",
                                "destination_uri_present" to "true",
                                "source_deleted_after_copy" to "true",
                            ),
                            null,
                        )
                    } else {
                        diagnostics.log(
                            DiagnosticLevel.ERROR,
                            "DOWNLOAD_DESTINATION_COPY_FAILED",
                            "فشل حفظ الوسيط المكتمل في المسار المحدد",
                            "download.destination",
                            mapOf(
                                "task_id" to task.id,
                                "destination_mode" to "CUSTOM_DIRECTORY",
                                "local_recovery_file_present" to localFile.exists().toString(),
                                "failure" to (copied.exceptionOrNull()?.message ?: "unknown"),
                            ),
                            copied.exceptionOrNull(),
                        )
                        persistDestinationFailure(completedRecord, "تعذر الكتابة في مجلد التنزيل المحدد.")
                        return DownloadDestinationPublishOutcome.Failed("تعذر الكتابة في مجلد التنزيل المحدد.")
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val localFile = java.io.File(task.destinationPath)
                    val published = MediaStorePublisher(applicationContext).publish(localFile)
                    if (published.isFailure) {
                        val detail = "تم تنزيل الملف، لكن تعذر حفظه في مكتبة الوسائط. بقيت نسخة استرداد محلية."
                        persistDestinationFailure(completedRecord, detail)
                        return DownloadDestinationPublishOutcome.Failed(detail)
                    }
                    completedRecord = completedRecord.copy(
                        destinationUri = published.getOrThrow().toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    // Before Android 10, keep the file in app-specific external storage but
                    // provide a safe content:// URI so the app can open, share, and delete it.
                    val localFile = java.io.File(task.destinationPath)
                    if (!localFile.isFile || localFile.length() <= 0L) {
                        val detail = "اكتمل النقل دون ملف محلي صالح للنشر."
                        persistDestinationFailure(completedRecord, detail)
                        return DownloadDestinationPublishOutcome.Failed(detail)
                    }
                    val destinationUri = FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.fileprovider",
                        localFile,
                    )
                    completedRecord = completedRecord.copy(
                        destinationUri = destinationUri.toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                if (completedRecord !== record) {
                    repository.upsert(completedRecord)
                }
                }
        return DownloadDestinationPublishOutcome.Published(completedRecord)
    }

    private suspend fun persistDestinationFailure(
        record: DownloadRecord,
        detail: String,
    ) {
        runCatching {
            (applicationContext as AHDownloadApplication).downloadRepository.upsert(
                record.copy(
                    status = DownloadStatus.FAILED,
                    failureCode = "destination_storage_error",
                    failureDetail = detail,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }
}
