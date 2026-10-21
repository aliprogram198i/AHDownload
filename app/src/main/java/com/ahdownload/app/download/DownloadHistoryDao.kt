package com.ahdownload.app.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {
    @Query("SELECT * FROM download_history ORDER BY updatedAtEpochMs DESC, taskId ASC")
    fun observeAll(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM download_history ORDER BY updatedAtEpochMs DESC, taskId ASC")
    suspend fun listHistory(): List<DownloadHistoryEntity>

    @Query("SELECT * FROM download_history WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: String): DownloadHistoryEntity?

    @Query(
        "SELECT * FROM download_history " +
            "WHERE contentFingerprint = :fingerprint " +
            "ORDER BY updatedAtEpochMs DESC LIMIT 1",
    )
    suspend fun findByContentFingerprint(fingerprint: String): DownloadHistoryEntity?

    @Query(
        "SELECT * FROM download_history WHERE status IN (:statuses) " +
            "ORDER BY createdAtEpochMs ASC, taskId ASC",
    )
    suspend fun listActive(statuses: List<String>): List<DownloadHistoryEntity>

    @Query("SELECT COUNT(*) FROM download_history")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadHistoryEntity>)

    /** Legacy import must never overwrite any record already committed to the new database. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLegacyIfAbsent(entities: List<DownloadHistoryEntity>)

    @Query("DELETE FROM download_history WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}
