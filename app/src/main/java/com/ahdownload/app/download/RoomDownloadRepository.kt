package com.ahdownload.app.download

import android.content.Context
import androidx.core.util.AtomicFile
import androidx.room.withTransaction
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.DownloadRepository
import com.ahdownload.domain.download.DownloadStatus
import com.ahdownload.domain.download.DownloadTemporaryFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Room-backed download history with indexed status/fingerprint lookups.
 *
 * The prior AtomicFile JSON store is imported once before any public repository operation.
 * Existing record JSON remains the compatibility payload so task metadata and request headers
 * do not need a lossy schema conversion during migration.
 */
class RoomDownloadRepository(context: Context) : DownloadRepository {
    private val appContext = context.applicationContext
    private val database = DownloadHistoryDatabase.getInstance(appContext)
    private val dao = database.downloadHistoryDao()
    private val codec = DownloadRecordJsonCodec()
    private val legacyFile = File(appContext.filesDir, "downloads/downloads.json")
    private val legacyStore = AtomicFile(legacyFile)
    private val migrationPreferences = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    private val migrationMutex = Mutex()

    @Volatile
    private var migrationReady = false

    override fun observeHistory(): Flow<List<DownloadRecord>> = flow {
        ensureLegacyMigrated()
        emitAll(dao.observeAll().map { rows -> rows.mapNotNull(::decodeEntity) })
    }

    override suspend fun upsert(record: DownloadRecord) = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.upsert(toEntity(record))
    }

    override suspend fun get(taskId: String): DownloadRecord? = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.get(taskId)?.let(::decodeEntity)
    }

    override suspend fun delete(taskId: String) = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.delete(taskId)
    }

    override suspend fun listHistory(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.listHistory().mapNotNull(::decodeEntity)
    }

    override suspend fun findByContentFingerprint(fingerprint: String?): DownloadRecord? =
        withContext(Dispatchers.IO) {
            ensureLegacyMigrated()
            val normalized = fingerprint?.takeIf { it.isNotBlank() } ?: return@withContext null
            dao.findByContentFingerprint(normalized)?.let(::decodeEntity)
        }

    override suspend fun listActive(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        dao.listActive(ACTIVE_STATUSES.map { it.name }).mapNotNull(::decodeEntity)
    }

    override suspend fun recoverInterrupted(nowEpochMs: Long): List<DownloadRecord> =
        withContext(Dispatchers.IO) {
            ensureLegacyMigrated()
            database.withTransaction {
                val records = dao.listHistory().mapNotNull(::decodeEntity)
                val recovered = records
                    .filter { it.status in INTERRUPTED_STATUSES }
                    .map { record ->
                        val partialSize = DownloadTemporaryFiles.recoverableTemporaryFiles(record.task)
                            .firstOrNull { it.isFile }
                            ?.let { file -> runCatching { file.length() }.getOrDefault(0L) }
                            ?.coerceAtLeast(0L)
                            ?: 0L
                        record.copy(
                            status = DownloadStatus.QUEUED,
                            bytesDownloaded = partialSize,
                            totalBytes = record.totalBytes?.coerceAtLeast(partialSize),
                            failureCode = null,
                            failureDetail = null,
                            updatedAtEpochMs = maxOf(nowEpochMs, record.createdAtEpochMs, record.updatedAtEpochMs),
                        )
                    }

                if (recovered.isNotEmpty()) {
                    dao.upsertAll(recovered.map(::toEntity))
                }
                recovered
            }
        }

    private suspend fun ensureLegacyMigrated() {
        if (migrationReady) return
        migrationMutex.withLock {
            if (migrationReady) return@withLock
            withContext(Dispatchers.IO) {
                if (migrationPreferences.getBoolean(MIGRATION_KEY, false)) {
                    migrationReady = true
                    return@withContext
                }

                val legacyBackup = File(legacyFile.absolutePath + ".bak")
                if (legacyFile.exists() || legacyBackup.exists()) {
                    val bytes = try {
                        legacyStore.readFully()
                    } catch (error: IOException) {
                        preserveUnreadableLegacyStore()
                        byteArrayOf()
                    }
                    val decoded = codec.decode(String(bytes, StandardCharsets.UTF_8))
                    if (bytes.isNotEmpty() && decoded.isEmpty()) {
                        // Keep an on-device recovery copy instead of silently discarding
                        // malformed legacy data; the new repository remains available.
                        preserveUnreadableLegacyStore()
                    } else if (decoded.isNotEmpty()) {
                        dao.insertLegacyIfAbsent(decoded.map(::toEntity))
                    }
                    legacyStore.delete()
                }

                if (!migrationPreferences.edit().putBoolean(MIGRATION_KEY, true).commit()) {
                    throw IOException("Unable to persist download-history migration marker")
                }
                migrationReady = true
            }
        }
    }

    private fun preserveUnreadableLegacyStore() {
        val parent = legacyFile.parentFile ?: return
        if (!parent.exists()) return
        val backup = File(parent, "downloads.json.unreadable-backup")
        val possibleSources = listOf(legacyFile, File(legacyFile.absolutePath + ".bak"))
        possibleSources.firstOrNull { it.isFile }?.let { source ->
            runCatching { source.copyTo(backup, overwrite = true) }
        }
    }

    private fun toEntity(record: DownloadRecord): DownloadHistoryEntity =
        DownloadHistoryEntity(
            taskId = record.task.id,
            contentFingerprint = record.task.contentFingerprint,
            status = record.status.name,
            createdAtEpochMs = record.createdAtEpochMs,
            updatedAtEpochMs = record.updatedAtEpochMs,
            recordJson = codec.encode(listOf(record)),
        )

    private fun decodeEntity(entity: DownloadHistoryEntity): DownloadRecord? =
        runCatching { codec.decode(entity.recordJson).firstOrNull() }
            .getOrNull()
            ?.takeIf { it.task.id == entity.taskId }

    private companion object {
        const val MIGRATION_PREFS = "ahdownload_download_history_migration"
        const val MIGRATION_KEY = "atomic_json_to_room_v1"
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
