package com.ahdownload.app.download

import android.content.Context
import androidx.core.util.AtomicFile
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
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
    private val lock = Any()
    private val storeFile = File(
        context.applicationContext.filesDir,
        "downloads/downloads.json",
    )
    private val store = AtomicFile(storeFile)
    private val codec = DownloadRecordJsonCodec()
    private val historyFlow = MutableStateFlow<List<DownloadRecord>>(emptyList())

    init {
        storeFile.parentFile?.mkdirs()
        historyFlow.value = runCatching {
            synchronized(lock) { readLocked().sortedByDescending { it.updatedAtEpochMs } }
        }.getOrDefault(emptyList())
    }

    override fun observeHistory(): Flow<List<DownloadRecord>> = historyFlow.asStateFlow()

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

    override suspend fun delete(taskId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val records = readLocked().filterNot { it.task.id == taskId }
            if (records.size != readLocked().size) {
                writeLocked(records)
            }
        }
    }

    override suspend fun listHistory(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            readLocked().sortedByDescending { it.updatedAtEpochMs }
        }
    }

    override suspend fun findByContentFingerprint(fingerprint: String?): DownloadRecord? =
        withContext(Dispatchers.IO) {
            if (fingerprint.isNullOrBlank()) return@withContext null
            synchronized(lock) {
                readLocked().firstOrNull { it.task.contentFingerprint == fingerprint }
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
                        run {
                            val partialSize = runCatching {
                                File(it.task.destinationPath + ".part").takeIf { file -> file.isFile }?.length() ?: 0L
                            }.getOrDefault(0L).coerceAtLeast(0L)
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

    private fun readLocked(): List<DownloadRecord> {
        if (!store.baseFile.exists()) return emptyList()
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
