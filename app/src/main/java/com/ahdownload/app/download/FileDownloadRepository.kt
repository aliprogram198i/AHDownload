package com.ahdownload.app.download

import android.content.Context
import androidx.core.util.AtomicFile
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTemporaryFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class FileDownloadRepository(
    context: Context,
) : DownloadRepository {
    // All repository instances for this app process coordinate through one file lock.
    private val lock = PROCESS_LOCK
    private val storeFile = File(
        context.applicationContext.filesDir,
        "downloads/downloads.json",
    )
    private val store = AtomicFile(storeFile)
    private val codec = DownloadRecordJsonCodec()
    private val historyFlow: MutableStateFlow<List<DownloadRecord>> = synchronized(lock) {
        storeFile.parentFile?.mkdirs()
        val key = storeFile.absolutePath
        val existing = sharedHistoryFlows[key]
        if (existing != null) {
            // A missing base and backup file means the store was intentionally cleared.
            if (!storeFile.exists() && !File(storeFile.absolutePath + ".bak").exists()) {
                existing.value = emptyList()
            }
            existing
        } else {
            val initial = runCatching {
                readLocked().sortedByDescending { it.updatedAtEpochMs }
            }.getOrDefault(emptyList())
            MutableStateFlow(initial).also { sharedHistoryFlows[key] = it }
        }
    }

    override fun observeHistory(): Flow<List<DownloadRecord>> = historyFlow.asStateFlow()

    override suspend fun upsert(record: DownloadRecord) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val records = historyFlow.value.associateBy { it.task.id }.toMutableMap()
            records[record.task.id] = record
            writeLocked(records.values.toList())
        }
    }

    override suspend fun get(taskId: String): DownloadRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            historyFlow.value.firstOrNull { it.task.id == taskId }
        }
    }

    override suspend fun delete(taskId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val previous = historyFlow.value
            val records = previous.filterNot { it.task.id == taskId }
            if (records.size != previous.size) {
                writeLocked(records)
            }
        }
    }

    override suspend fun listHistory(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            historyFlow.value.sortedByDescending { it.updatedAtEpochMs }
        }
    }

    override suspend fun findByContentFingerprint(fingerprint: String?): DownloadRecord? =
        withContext(Dispatchers.IO) {
            if (fingerprint.isNullOrBlank()) return@withContext null
            synchronized(lock) {
                historyFlow.value.firstOrNull { it.task.contentFingerprint == fingerprint }
            }
        }

    override suspend fun listActive(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            historyFlow.value
                .filter { it.status in ACTIVE_STATUSES }
                .sortedBy { it.createdAtEpochMs }
        }
    }

    override suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val records = historyFlow.value.toMutableList()
                val recovered = records
                    .filter { it.status in INTERRUPTED_STATUSES }
                    .map {
                        run {
                            val partialSize = DownloadTemporaryFiles.recoverableTemporaryFiles(it.task)
                                .firstOrNull { file -> file.isFile }
                                ?.let { file -> runCatching { file.length() }.getOrDefault(0L) }
                                .orZero()
                            it.copy(
                                status = DownloadStatus.QUEUED,
                                bytesDownloaded = partialSize,
                                totalBytes = it.totalBytes?.coerceAtLeast(partialSize),
                                failureCode = null,
                                failureDetail = null,
                                updatedAtEpochMs = nowEpochMs,
                            )
                        }
                    }

                if (recovered.isEmpty()) {
                    return@withContext emptyList()
                }

                val byId = recovered.associateBy { it.task.id }
                for (index in records.indices) {
                    val replacement = byId[records[index].task.id]
                    if (replacement != null) records[index] = replacement
                }
                writeLocked(records)
                recovered
            }
        }

    private fun Long?.orZero(): Long = this?.coerceAtLeast(0L) ?: 0L

    private fun readLocked(): List<DownloadRecord> {
        // AtomicFile can restore a backup left by a process interruption during write.
        if (!store.baseFile.exists() && !File(store.baseFile.absolutePath + ".bak").exists()) return emptyList()
        val bytes = store.readFully()
        if (bytes.isEmpty()) return emptyList()
        return codec.decode(String(bytes, StandardCharsets.UTF_8)).distinctBy { it.task.id }
    }

    private fun writeLocked(records: List<DownloadRecord>) {
        val sorted = records.sortedByDescending { it.updatedAtEpochMs }
        val payload = codec.encode(sorted).toByteArray(StandardCharsets.UTF_8)

        val output = store.startWrite()
        try {
            output.write(payload)
            output.flush()
            store.finishWrite(output)
        } catch (error: Throwable) {
            store.failWrite(output)
            throw error
        }

        historyFlow.value = sorted
    }

    private companion object {
        val PROCESS_LOCK = Any()
        val sharedHistoryFlows = mutableMapOf<String, MutableStateFlow<List<DownloadRecord>>>()

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
