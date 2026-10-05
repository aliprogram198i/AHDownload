package com.ahdownload.app.download

import android.content.Context
import androidx.core.util.AtomicFile
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class FileDownloadRepository(
    context: Context,
) : DownloadRepository {
    private val lock = Any()
    private val store = AtomicFile(
        File(context.applicationContext.filesDir, "downloads/downloads.json"),
        "downloads",
    )
    private val codec = DownloadRecordJsonCodec()

    override suspend fun upsert(record: DownloadRecord) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val records = readLocked().associateBy { it.task.id }.toMutableMap()
            records[record.task.id] = record
            writeLocked(records.values.toList())
        }
    }

    override suspend fun get(taskId: String): DownloadRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            readLocked().firstOrNull { it.task.id == taskId }
        }
    }

    override suspend fun listHistory(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            readLocked()
                .sortedByDescending { it.updatedAtEpochMs }
        }
    }

    override suspend fun listActive(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            readLocked()
                .filter { it.status in ACTIVE_STATUSES }
                .sortedBy { it.createdAtEpochMs }
        }
    }

    override suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val records = readLocked().toMutableList()
                val recovered = records
                    .filter { it.status in INTERRUPTED_STATUSES }
                    .map {
                        it.copy(
                            status = DownloadStatus.QUEUED,
                            bytesDownloaded = 0,
                            totalBytes = null,
                            failureCode = null,
                            failureDetail = null,
                            updatedAtEpochMs = nowEpochMs,
                        )
                    }

                if (recovered.isEmpty()) {
                    return@withContext emptyList()
                }

                val byId = recovered.associateBy { it.task.id }
                for (index in records.indices) {
                    val replacement = byId[records[index].task.id]
                    if (replacement != null) {
                        records[index] = replacement
                    }
                }
                writeLocked(records)
                recovered
            }
        }

    private fun readLocked(): List<DownloadRecord> {
        if (!store.baseFile.exists()) {
            return emptyList()
        }

        val bytes = store.readFully()
        if (bytes.isEmpty()) {
            return emptyList()
        }

        return codec.decode(String(bytes, StandardCharsets.UTF_8))
            .distinctBy { it.task.id }
    }

    private fun writeLocked(records: List<DownloadRecord>) {
        val payload = codec.encode(
            records.sortedByDescending { it.updatedAtEpochMs },
        ).toByteArray(StandardCharsets.UTF_8)

        val output = store.startWrite()
        try {
            output.write(payload)
            output.flush()
            store.finishWrite(output)
        } catch (error: Throwable) {
            store.failWrite(output)
            throw error
        }
    }
    
    private companion object {
        val ACTIVE_STATUSES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING,
        )
        val INTERRUPTED_STATUSES = setOf(
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING,
        )
    }
}
